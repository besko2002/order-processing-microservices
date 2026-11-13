package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.Topics;
import com.example.orders.support.outbox.OutboxEvent;
import com.example.orders.support.outbox.OutboxRelay;
import com.example.orders.support.correlation.CorrelationId;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** The relay end of the outbox: what actually lands on the topic, and what happens to the row. */
class OutboxRelayTest extends OrderIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theRelayPublishesOrderCreatedAndMarksTheRowPublished() throws Exception {
        UUID chair = givenCatalogProduct("SKU-CHAIR", "Office chair", "149.00");
        UUID user = UUID.randomUUID();
        String correlationId = "corr-" + UUID.randomUUID();

        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .header(CorrelationId.HEADER, correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(chair, 2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(parse(body).get("id").asText());

        // The scheduler drains the outbox on its own; no test hook is needed for the happy path.
        awaitUntil(Duration.ofSeconds(30), () -> outbox
                .findByAggregateIdOrderByCreatedAtAsc(orderId)
                .stream()
                .allMatch(event -> event.getPublishedAt() != null));

        OutboxEvent row = outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).getFirst();
        assertThat(row.getPublishedAt()).isNotNull();
        assertThat(row.getLastError()).isNull();

        ConsumerRecord<String, String> record = consumeOrderEvent(orderId);
        // Key = aggregate id, so every event of one order keeps its partition and its order.
        assertThat(record.key()).isEqualTo(orderId.toString());
        assertThat(header(record, EventHeaders.EVENT_TYPE)).isEqualTo("OrderCreated");
        assertThat(header(record, EventHeaders.EVENT_ID)).isEqualTo(row.getId().toString());
        assertThat(header(record, EventHeaders.CORRELATION_ID)).isEqualTo(correlationId);

        JsonNode envelope = parse(record.value());
        assertThat(envelope.get("eventId").asText()).isEqualTo(row.getId().toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("OrderCreated");
        assertThat(envelope.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(envelope.get("occurredAt").asText()).isNotBlank();

        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(payload.get("orderNumber").asText()).startsWith("ORD-");
        assertThat(payload.get("customerId").asText()).isEqualTo(user.toString());
        assertThat(payload.get("totalAmount").asDouble()).isEqualTo(298.00);
        assertThat(payload.get("currency").asText()).isEqualTo("EUR");
        assertThat(payload.get("items")).hasSize(1);
        assertThat(payload.get("items").get(0).get("productId").asText()).isEqualTo(chair.toString());
        assertThat(payload.get("items").get(0).get("sku").asText()).isEqualTo("SKU-CHAIR");
        assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void anAlreadyPublishedRowIsNeverClaimedAgain() throws Exception {
        UUID mug = givenCatalogProduct("SKU-MUG", "Coffee mug", "9.00");

        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(mug, 1)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(parse(body).get("id").asText());

        awaitUntil(Duration.ofSeconds(30), () -> outbox
                .findByAggregateIdOrderByCreatedAtAsc(orderId).getFirst().getPublishedAt() != null);
        OffsetDateTime publishedAt =
                outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).getFirst().getPublishedAt();

        // A further poll finds nothing to do: the partial index only covers published_at IS NULL.
        assertThat(relay.publishPending()).isZero();
        assertThat(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc()).isEmpty();
        assertThat(outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).getFirst().getPublishedAt())
                .isEqualTo(publishedAt);
    }

    @Test
    void cleanupDropsOldPublishedRowsAndKeepsEverythingElse() throws Exception {
        UUID pen = givenCatalogProduct("SKU-PEN", "A pen", "2.00");

        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(pen, 1)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(parse(body).get("id").asText());

        awaitUntil(Duration.ofSeconds(30), () -> outbox
                .findByAggregateIdOrderByCreatedAtAsc(orderId).getFirst().getPublishedAt() != null);

        // Fresh rows stay, whatever their state.
        assertThat(relay.purgePublished()).isZero();
        assertThat(outbox.count()).isEqualTo(1);

        // Backdate it past the P7D retention window and it goes.
        jdbc.update("UPDATE outbox_events SET published_at = ? WHERE aggregate_id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(30), orderId);
        assertThat(relay.purgePublished()).isEqualTo(1);
        assertThat(outbox.count()).isZero();
        // The order itself is untouched: the outbox is a queue, not the record of the business fact.
        assertThat(orders.existsById(orderId)).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private ConsumerRecord<String, String> consumeOrderEvent(UUID orderId) {
        Properties properties = new Properties();
        properties.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(Topics.ORDER_EVENTS));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    // Other tests publish to the same topic; take only this order's event.
                    if (orderId.toString().equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No OrderCreated event for order " + orderId + " arrived on "
                + Topics.ORDER_EVENTS);
    }
}
