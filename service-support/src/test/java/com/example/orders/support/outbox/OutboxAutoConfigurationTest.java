package com.example.orders.support.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.Topics;
import com.example.orders.support.SupportIntegrationTest;
import com.example.orders.support.correlation.CorrelationId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox as a service gets it from this module: opt-in, transaction-bound, and relayed to a real
 * broker.
 */
class OutboxAutoConfigurationTest extends SupportIntegrationTest {

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetState() {
        outbox.deleteAllInBatch();
        MDC.clear();
    }

    @Test
    void theMachineryStaysAbsentUntilAServiceOptsIn() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(OutboxWriter.class)
                        .doesNotHaveBean(OutboxRelay.class)
                        .doesNotHaveBean(OutboxRelayScheduler.class));
    }

    @Test
    void theServiceGetsTheWriterAndTheRelayWhenItOptsIn() {
        assertThat(writer).isNotNull();
        assertThat(relay).isNotNull();
        assertThat(outbox).isNotNull();
    }

    @Test
    void theTimerIsSeparatelySwitchable() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .withBean(KafkaProperties.class, KafkaProperties::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(OutboxRepository.class, () -> Mockito.mock(OutboxRepository.class))
                .withBean(OutboxRelay.class, () -> Mockito.mock(OutboxRelay.class));

        runner.withPropertyValues("outbox.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(OutboxRelayScheduler.class));

        // What OutboxRelayFailureTest in order-service relies on: no background poll, so a test can
        // drive the relay itself.
        runner.withPropertyValues("outbox.enabled=true", "outbox.scheduler-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OutboxRelayScheduler.class));
    }

    @Test
    void writingAnEventOutsideATransactionIsRefused() {
        // The whole point of the pattern: an event recorded outside the transaction that changed the
        // state could be committed while the state change rolls back.
        assertThatThrownBy(() -> writer.record(UUID.randomUUID(), "OrderCreated", Topics.ORDER_EVENTS, payload()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void aRolledBackTransactionLeavesNoEventBehind() {
        UUID aggregateId = UUID.randomUUID();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            writer.record(aggregateId, "OrderCreated", Topics.ORDER_EVENTS, payload());
            throw new IllegalStateException("the business transaction failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outbox.findByAggregateIdOrderByCreatedAtAsc(aggregateId)).isEmpty();
    }

    @Test
    void aRecordedEventCarriesTheEnvelopeTheCorrelationIdAndTheConfiguredAggregateType() {
        UUID aggregateId = UUID.randomUUID();
        MDC.put(CorrelationId.MDC_KEY, "corr-outbox-1");

        OutboxEvent row = transactions.execute(status ->
                writer.record(aggregateId, "OrderCreated", Topics.ORDER_EVENTS, payload()));

        assertThat(row).isNotNull();
        assertThat(row.getAggregateType()).isEqualTo("TestAggregate");
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getCorrelationId()).isEqualTo("corr-outbox-1");

        JsonNode envelope = parse(row.getPayload());
        assertThat(envelope.get("eventId").asText()).isEqualTo(row.getId().toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("OrderCreated");
        assertThat(envelope.get("correlationId").asText()).isEqualTo("corr-outbox-1");
        assertThat(envelope.get("payload").get("orderNumber").asText()).isEqualTo("ORD-TEST");
    }

    @Test
    void theRelayPublishesToKafkaWithKeyAndHeadersAndMarksTheRowPublished() {
        UUID aggregateId = UUID.randomUUID();
        String topic = uniqueTopic("support.outbox");
        MDC.put(CorrelationId.MDC_KEY, "corr-outbox-2");
        OutboxEvent row = transactions.execute(status ->
                writer.record(aggregateId, "OrderCreated", topic, payload()));

        assertThat(relay.publishPending()).isEqualTo(1);

        OutboxEvent published = outbox.findById(row.getId()).orElseThrow();
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getLastError()).isNull();
        // A second poll finds nothing: the partial index only covers published_at IS NULL.
        assertThat(relay.publishPending()).isZero();

        ConsumerRecord<String, String> record = consumeOne(topic);
        assertThat(record.key()).isEqualTo(aggregateId.toString());
        assertThat(header(record, EventHeaders.EVENT_TYPE)).isEqualTo("OrderCreated");
        assertThat(header(record, EventHeaders.EVENT_ID)).isEqualTo(row.getId().toString());
        assertThat(header(record, EventHeaders.CORRELATION_ID)).isEqualTo("corr-outbox-2");
        // The relay ships the stored bytes unchanged: what is on the topic is provably what was
        // committed.
        assertThat(record.value()).isEqualTo(published.getPayload());
    }

    @Test
    void cleanupDropsPublishedRowsPastTheRetentionWindowAndKeepsTheRest() {
        UUID oldAggregate = UUID.randomUUID();
        UUID freshAggregate = UUID.randomUUID();
        String topic = uniqueTopic("support.outbox.cleanup");
        transactions.executeWithoutResult(status -> {
            writer.record(oldAggregate, "OrderCreated", topic, payload());
            writer.record(freshAggregate, "OrderCreated", topic, payload());
        });
        assertThat(relay.publishPending()).isEqualTo(2);

        assertThat(relay.purgePublished()).isZero();

        jdbc.update("UPDATE outbox_events SET published_at = ? WHERE aggregate_id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(30), oldAggregate);

        assertThat(relay.purgePublished()).isEqualTo(1);
        assertThat(outbox.findByAggregateIdOrderByCreatedAtAsc(oldAggregate)).isEmpty();
        assertThat(outbox.findByAggregateIdOrderByCreatedAtAsc(freshAggregate)).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private static OrderCreated payload() {
        return new OrderCreated(
                UUID.randomUUID(), "ORD-TEST", UUID.randomUUID(), new BigDecimal("12.34"), "EUR", List.of());
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        }
        catch (Exception e) {
            throw new AssertionError("Stored payload is not JSON: " + body, e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ConsumerRecord<String, String> consumeOne(String topic) {
        Properties properties = new Properties();
        properties.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-assert-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    return record;
                }
            }
        }
        throw new AssertionError("Nothing arrived on " + topic);
    }
}
