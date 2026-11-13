package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.Topics;
import com.example.orders.support.outbox.OutboxEvent;
import com.example.orders.support.outbox.OutboxRelay;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;

/**
 * What the relay does when the broker will not take the event.
 *
 * <p>The outage is simulated with a {@link KafkaTemplate} that fails the send instead of by
 * stopping the container: the failure is then instant, selective and reversible, so the recovery
 * ("published once Kafka is back") can be asserted in the same test without a container restart.
 *
 * <p>The scheduler is switched off here so the relay only runs when the test says so — otherwise
 * a background poll would race every assertion about {@code attempts}.
 */
@TestPropertySource(properties = "outbox.scheduler-enabled=false")
class OutboxRelayFailureTest extends OrderIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private ToggleableKafkaTemplate kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void healTheBroker() {
        kafka.heal();
    }

    @Test
    void aFailedSendLeavesTheRowForTheNextPollAndPublishesItOnceKafkaIsBack() throws Exception {
        UUID shelf = givenCatalogProduct("SKU-SHELF", "Book shelf", "89.00");
        UUID orderId = placeOrder(shelf, 1);

        kafka.breakEverything();

        assertThat(relay.publishPending()).isZero();
        OutboxEvent afterFirstAttempt = rowOf(orderId);
        assertThat(afterFirstAttempt.getPublishedAt()).isNull();
        assertThat(afterFirstAttempt.getAttempts()).isEqualTo(1);
        assertThat(afterFirstAttempt.getLastError()).contains("TimeoutException");

        // The event is not dropped and not dead-lettered: it stays claimable.
        assertThat(relay.publishPending()).isZero();
        assertThat(rowOf(orderId).getAttempts()).isEqualTo(2);
        assertThat(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc()).hasSize(1);

        kafka.heal();

        assertThat(relay.publishPending()).isEqualTo(1);
        OutboxEvent published = rowOf(orderId);
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getLastError()).isNull();
        // The failed attempts stay on the row as the operational trace of the outage.
        assertThat(published.getAttempts()).isEqualTo(2);
        assertThat(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc()).isEmpty();
    }

    @Test
    void oneStuckOrderDoesNotStallTheOthers() throws Exception {
        UUID product = givenCatalogProduct("SKU-PAR", "Parallel", "10.00");
        UUID stuck = placeOrder(product, 1);
        UUID healthy = placeOrder(product, 2);

        kafka.breakKey(stuck.toString());

        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(rowOf(stuck).getPublishedAt()).isNull();
        assertThat(rowOf(stuck).getAttempts()).isEqualTo(1);
        assertThat(rowOf(healthy).getPublishedAt()).isNotNull();
    }

    @Test
    void aLaterEventOfTheSameOrderNeverOvertakesAStuckOne() throws Exception {
        UUID product = givenCatalogProduct("SKU-SEQ", "Sequenced", "10.00");
        UUID orderId = placeOrder(product, 1);
        UUID laterEventId = appendEventFor(orderId);

        kafka.breakKey(orderId.toString());

        // Both rows are claimed in one batch; the first fails, so the second is held back rather
        // than delivered out of sequence.
        assertThat(relay.publishPending()).isZero();
        assertThat(outbox.findById(laterEventId).orElseThrow().getPublishedAt()).isNull();
        assertThat(outbox.findById(laterEventId).orElseThrow().getAttempts()).isZero();

        kafka.heal();

        assertThat(relay.publishPending()).isEqualTo(2);
        List<OutboxEvent> events = outbox.findByAggregateIdOrderByCreatedAtAsc(orderId);
        assertThat(events).hasSize(2).allSatisfy(event ->
                assertThat(event.getPublishedAt()).isNotNull());
    }

    // ------------------------------------------------------------------ helpers

    private UUID placeOrder(UUID product, int quantity) throws Exception {
        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(parse(body).get("id").asText());
    }

    private OutboxEvent rowOf(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).getFirst();
    }

    /** Adds a second, later event for the same order — phase 6 will produce these for real. */
    private UUID appendEventFor(UUID orderId) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, topic, payload, correlation_id,
                     created_at, attempts)
                VALUES (?, 'Order', ?, 'OrderConfirmed', ?, ?::jsonb, ?, ?, 0)
                """,
                eventId,
                orderId,
                Topics.ORDER_EVENTS,
                "{\"eventId\":\"%s\",\"eventType\":\"OrderConfirmed\",\"payload\":{}}".formatted(eventId),
                "seq-test",
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        return eventId;
    }

    // ------------------------------------------------------------------ the broken producer

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingProducerConfiguration {

        /**
         * {@code @Primary} rather than a bean-definition override: both templates exist, and the
         * relay simply gets this one injected.
         */
        @Bean
        @Primary
        ToggleableKafkaTemplate toggleableKafkaTemplate(
                @Qualifier("outboxKafkaTemplate") KafkaTemplate<String, String> real) {
            return new ToggleableKafkaTemplate(real.getProducerFactory());
        }
    }

    /** A real producer that can be told to fail every send, or only the ones for a given key. */
    static class ToggleableKafkaTemplate extends KafkaTemplate<String, String> {

        private final Set<String> failingKeys = ConcurrentHashMap.newKeySet();
        private volatile boolean failEverything;

        ToggleableKafkaTemplate(ProducerFactory<String, String> producerFactory) {
            super(producerFactory);
        }

        void breakEverything() {
            this.failEverything = true;
        }

        void breakKey(String key) {
            this.failingKeys.add(key);
        }

        void heal() {
            this.failEverything = false;
            this.failingKeys.clear();
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
            if (failEverything || failingKeys.contains(record.key())) {
                CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
                failed.completeExceptionally(new TimeoutException("Simulated broker outage"));
                return failed;
            }
            return super.send(record);
        }
    }
}
