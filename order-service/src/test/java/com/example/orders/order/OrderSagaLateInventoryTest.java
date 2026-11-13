package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.StockRejected;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import com.example.orders.support.correlation.CorrelationId;
import com.example.orders.support.idempotency.ProcessedEventRepository;
import com.example.orders.support.outbox.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Priority 2, order-service half: the saga's <b>defensive late {@code StockReserved}</b>.
 *
 * <p>inventory-service now refuses to hold stock for an order whose release already arrived
 * (see {@code InventorySagaReleaseIntentTest}). That closes the hole where the release was
 * consumed <em>before</em> the reservation existed. But the answer can also arrive the other way
 * around — the {@code StockReserved} event escapes, the saga has already cancelled the order,
 * and the saga-side {@code ReleaseStock} was consumed without anything to release. The saga's
 * guard is the second half of the fix: a late stock reservation is <em>remembered</em> (its id
 * would otherwise be unknown and unreleasable), and a {@code ReleaseStock} is queued again in
 * the same transaction, covering both the "reservation after cancellation" case and the one where
 * the first release lost the race to the reservation's row.
 */
class OrderSagaLateInventoryTest extends OrderIntegrationTest {

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void clearDeduplicationLog() {
        processedEvents.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ late reservation

    @Test
    void aStockReservedForACancelledOrderIsRememberedAndReleasedAgain() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "119.00", 1, "corr-late-reserve");

        // The saga gives up before any stock answer exists.
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        // The unconditional release the cancellation queued (there was no reservation to name).
        assertThat(countOutbox(orderId, "ReleaseStock")).isEqualTo(1);

        // The reservation escapes the cancellation and only now reaches the saga.
        UUID reservationId = UUID.randomUUID();
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-1",
                new StockReserved(orderId, reservationId, List.of())));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getReservationId() != null);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            Order order = order(orderId);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getReservationId()).isEqualTo(reservationId);
            assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.NONE);
        });
        // The late answer queued the release that cancels it out, in the same commit.
        assertThat(countOutbox(orderId, "ReleaseStock")).isEqualTo(2);
        assertThat(outboxTypes(orderId)).doesNotContain("ProcessPayment", "OrderConfirmed", "CommitStock");
        // The compensation was not announced twice.
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
    }

    @Test
    void furtherLateStockReservedEventsForTheSameOrderQueueNothingMore() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "119.00", 1, "corr-late-reserve-dupes");
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-dupes-a",
                new StockReserved(orderId, UUID.randomUUID(), List.of())));
        // Wait until the first late answer is processed before naming the reservation, so the
        // rest of the test is not a race against the consumer.
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getReservationId() != null);
        UUID remembered = order(orderId).getReservationId();

        // The broker redelivers the same event id... and a retrying inventory-service re-answers
        // with a fresh event id for the same reservation. The saga already remembers it.
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-dupes-a2",
                new StockReserved(orderId, remembered, List.of())));
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-dupes-b",
                new StockReserved(orderId, UUID.randomUUID(), List.of())));

        awaitUntil(Duration.ofSeconds(45), () -> processedEvents.count() == 3);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.NONE);
            // One release from the cancel plus one from the first late answer; nothing more.
            assertThat(countOutbox(orderId, "ReleaseStock")).isEqualTo(2);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    @Test
    void aStockReservedForARejectedOrderIsRememberedAndReleasedToo() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID(), "119.00", 1, "corr-late-reserve-rejected");

        // Inventory first said no (REJECTED is final and never queues a release of its own)...
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-rejected-no",
                new StockRejected(orderId, "Insufficient stock (SKU-X: requested 1, available 0)", List.of("SKU-X"))));
        awaitStatus(orderId, OrderStatus.REJECTED);
        assertThat(countOutbox(orderId, "ReleaseStock")).isZero();

        // ...and a later duplicate OrderCreated (a replay past the de-duplication window) was
        // reserved after a restock: this StockReserved is stock nobody will ever give back unless
        // the saga says so. The order stays REJECTED; the hold is remembered and released.
        UUID reservationId = UUID.randomUUID();
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-rejected-yes",
                new StockReserved(orderId, reservationId, List.of())));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getReservationId() != null);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            Order order = order(orderId);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.getReservationId()).isEqualTo(reservationId);
            assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.NONE);
            assertThat(countOutbox(orderId, "ReleaseStock")).isEqualTo(1);
        });
        assertThat(outboxTypes(orderId)).doesNotContain("ProcessPayment", "OrderConfirmed", "CommitStock");

        // A redelivery or a re-answer for the same reservation queues nothing more.
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve-rejected-again",
                new StockReserved(orderId, reservationId, List.of())));
        awaitUntil(Duration.ofSeconds(45), () -> processedEvents.count() == 3);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(countOutbox(orderId, "ReleaseStock")).isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------ helpers

    private Order order(UUID orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    private UUID placeOrder(UUID user, String unitPrice, int quantity, String correlationId) throws Exception {
        UUID product = givenCatalogProduct("SKU-LATERSV-" + UUID.randomUUID().toString().substring(0, 6),
                "Late reservation product", unitPrice);
        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .header(CorrelationId.HEADER, correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(parse(body).get("id").asText());
    }

    private void awaitStatus(UUID orderId, OrderStatus expected) {
        awaitUntil(Duration.ofSeconds(45), () -> orders.findById(orderId)
                .map(found -> found.getStatus() == expected)
                .orElse(false));
    }

    private OutboxEvent awaitOutbox(UUID orderId, String eventType) {
        awaitUntil(Duration.ofSeconds(45), () -> findOutbox(orderId, eventType).isPresent());
        return findOutbox(orderId, eventType).orElseThrow();
    }

    private Optional<OutboxEvent> findOutbox(UUID orderId, String eventType) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> eventType.equals(row.getEventType()))
                .findFirst();
    }

    private long countOutbox(UUID orderId, String eventType) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> eventType.equals(row.getEventType()))
                .count();
    }

    private List<String> outboxTypes(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .map(OutboxEvent::getEventType)
                .toList();
    }

    private void publish(String topic, UUID key, EventEnvelope<?> envelope) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic, key.toString(), json.writeValueAsString(envelope));
            record.headers().add(EventHeaders.EVENT_ID,
                    envelope.eventId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE,
                    envelope.eventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.CORRELATION_ID,
                    envelope.correlationId().getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while producing", e);
        }
        catch (Exception e) {
            throw new AssertionError("Could not produce onto " + topic, e);
        }
    }

    private static void assertHoldsFor(Duration duration, Runnable assertion) {
        long deadline = System.nanoTime() + duration.toNanos();
        do {
            assertion.run();
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting", e);
            }
        }
        while (System.nanoTime() < deadline);
    }
}
