package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.OrderItem;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.common.events.Topics;
import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.support.idempotency.ProcessedEventRepository;
import com.example.orders.support.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * inventory-service inside the saga, driven the way production drives it: messages in on Kafka,
 * messages out on Kafka, and the stock columns as the evidence in between.
 */
class InventorySagaTest extends InventoryIntegrationTest {

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void clearMessagingTables() {
        outbox.deleteAllInBatch();
        processedEvents.deleteAllInBatch();
    }

    @Test
    void anOrderCreatedEventReservesTheStockAndAnnouncesStockReserved() {
        Product chair = givenProduct("SKU-SAGA-1", 10);
        UUID orderId = UUID.randomUUID();

        publishOrderCreated(orderId, new OrderItem(chair.getId(), chair.getSku(), 3, new BigDecimal("10.00")));

        JsonNode event = awaitInventoryEvent(orderId, "StockReserved");
        assertThat(event.get("payload").get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(event.get("payload").get("reservationId").asText()).isNotBlank();
        // The correlation id of the incoming event travels into the outgoing one.
        assertThat(event.get("correlationId").asText()).isEqualTo("corr-saga-in");

        List<StockReservation> rows = reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getProductId()).isEqualTo(chair.getId());
            assertThat(row.getQuantity()).isEqualTo(3);
            assertThat(row.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        });
        Product after = products.findById(chair.getId()).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(7);
        assertThat(after.getReservedQuantity()).isEqualTo(3);
    }

    @Test
    void anOrderThatAsksForMoreThanIsAvailableIsRejectedWithItsShortages() {
        Product mug = givenProduct("SKU-SAGA-2", 2);
        UUID orderId = UUID.randomUUID();

        publishOrderCreated(orderId, new OrderItem(mug.getId(), mug.getSku(), 5, new BigDecimal("10.00")));

        JsonNode event = awaitInventoryEvent(orderId, "StockRejected");
        JsonNode payload = event.get("payload");
        assertThat(payload.get("reason").asText())
                .contains("Insufficient stock")
                .contains("SKU-SAGA-2")
                .contains("requested 5")
                .contains("available 2");
        assertThat(payload.get("unavailableSkus").get(0).asText()).isEqualTo("SKU-SAGA-2");

        // Nothing moved: a rejection must leave the whole order untouched.
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).isEmpty();
        Product after = products.findById(mug.getId()).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(2);
        assertThat(after.getReservedQuantity()).isZero();
    }

    @Test
    void aRedeliveredOrderCreatedReservesTheStockOnlyOnce() {
        Product desk = givenProduct("SKU-SAGA-3", 10);
        UUID orderId = UUID.randomUUID();
        OrderItem item = new OrderItem(desk.getId(), desk.getSku(), 4, new BigDecimal("10.00"));
        // The same event id twice: exactly what the outbox relay does when a broker acknowledgement
        // is lost after the record was already stored.
        EventEnvelope<OrderCreated> envelope = envelope(orderId, item);

        publish(Topics.ORDER_EVENTS, orderId, envelope);
        awaitUntil(Duration.ofSeconds(30),
                () -> !reservations.findByOrderIdOrderByCreatedAtAsc(orderId).isEmpty());
        publish(Topics.ORDER_EVENTS, orderId, envelope);

        assertHoldsFor(Duration.ofSeconds(3), () -> {
            assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).hasSize(1);
            Product after = products.findById(desk.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(6);
            assertThat(after.getReservedQuantity()).isEqualTo(4);
        });
        // One note per (event, consumer): the second delivery found it and did nothing.
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    void aCommitStockCommandTurnsTheHoldIntoASale() {
        Product lamp = givenProduct("SKU-SAGA-4", 10);
        UUID orderId = reserve(lamp, 2);

        publish(Topics.INVENTORY_COMMANDS, orderId, EventEnvelope.of("corr-commit", new CommitStock(orderId)));

        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().allMatch(row -> row.getStatus() == ReservationStatus.COMMITTED));
        Product after = products.findById(lamp.getId()).orElseThrow();
        // Sold: the units left "reserved" and did not come back to "available".
        assertThat(after.getAvailableQuantity()).isEqualTo(8);
        assertThat(after.getReservedQuantity()).isZero();
    }

    @Test
    void aReleaseStockCommandPutsAHoldBackOnTheShelf() {
        Product shelf = givenProduct("SKU-SAGA-5", 10);
        UUID orderId = reserve(shelf, 3);

        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-release", new ReleaseStock(orderId, null, "payment failed", List.of())));

        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().allMatch(row -> row.getStatus() == ReservationStatus.RELEASED));
        Product after = products.findById(shelf.getId()).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(10);
        assertThat(after.getReservedQuantity()).isZero();
    }

    @Test
    void releasingAnAlreadyCommittedOrderReturnsTheUnitsToStock() {
        Product pen = givenProduct("SKU-SAGA-6", 10);
        UUID orderId = reserve(pen, 5);
        publish(Topics.INVENTORY_COMMANDS, orderId, EventEnvelope.of("corr-commit", new CommitStock(orderId)));
        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().allMatch(row -> row.getStatus() == ReservationStatus.COMMITTED));
        assertThat(products.findById(pen.getId()).orElseThrow().getAvailableQuantity()).isEqualTo(5);

        // This is what a refund needs: the sale is undone, so the goods are on the shelf again.
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-refund", new ReleaseStock(orderId, null, "order cancelled", List.of())));

        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().allMatch(row -> row.getStatus() == ReservationStatus.RELEASED));
        Product after = products.findById(pen.getId()).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(10);
        assertThat(after.getReservedQuantity()).isZero();
    }

    @Test
    void aRedeliveredReleaseDoesNotReturnTheSameUnitsTwice() {
        Product box = givenProduct("SKU-SAGA-7", 10);
        UUID orderId = reserve(box, 4);
        EventEnvelope<ReleaseStock> release =
                EventEnvelope.of("corr-release", new ReleaseStock(orderId, null, "cancelled", List.of()));

        publish(Topics.INVENTORY_COMMANDS, orderId, release);
        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().allMatch(row -> row.getStatus() == ReservationStatus.RELEASED));
        publish(Topics.INVENTORY_COMMANDS, orderId, release);

        assertHoldsFor(Duration.ofSeconds(3), () -> {
            Product after = products.findById(box.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
        });
    }

    @Test
    void anEventTypeInventoryDoesNotCareAboutIsIgnored() {
        Product tray = givenProduct("SKU-SAGA-8", 10);
        UUID orderId = reserve(tray, 1);

        // OrderConfirmed travels on order.events too; it is a known type with nothing to do here.
        publish(Topics.ORDER_EVENTS, orderId, EventEnvelope.of("corr-ignored",
                new com.example.orders.common.events.OrderConfirmed(
                        orderId, "ORD-IGNORE", UUID.randomUUID(), new BigDecimal("10.00"), "EUR")));

        assertHoldsFor(Duration.ofSeconds(3), () -> {
            Product after = products.findById(tray.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(9);
            assertThat(after.getReservedQuantity()).isEqualTo(1);
            assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).hasSize(1);
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * @param product product to reserve from
     * @param quantity units to hold
     * @return the order id that now holds them, reserved through the real {@code OrderCreated} path
     */
    private UUID reserve(Product product, int quantity) {
        UUID orderId = UUID.randomUUID();
        publishOrderCreated(orderId,
                new OrderItem(product.getId(), product.getSku(), quantity, new BigDecimal("10.00")));
        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().anyMatch(row -> row.getStatus() == ReservationStatus.RESERVED));
        return orderId;
    }

    private void publishOrderCreated(UUID orderId, OrderItem... items) {
        publish(Topics.ORDER_EVENTS, orderId, envelope(orderId, items));
    }

    private static EventEnvelope<OrderCreated> envelope(UUID orderId, OrderItem... items) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : items) {
            total = total.add(item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())));
        }
        return EventEnvelope.of("corr-saga-in", new OrderCreated(
                orderId, "ORD-SAGA", UUID.randomUUID(), total, "EUR", List.of(items)));
    }

    private void publish(String topic, UUID key, EventEnvelope<?> envelope) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic, key.toString(), json.writeValueAsString(envelope));
            record.headers().add(EventHeaders.EVENT_ID, bytes(envelope.eventId().toString()));
            record.headers().add(EventHeaders.EVENT_TYPE, bytes(envelope.eventType()));
            record.headers().add(EventHeaders.CORRELATION_ID, bytes(envelope.correlationId()));
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

    private JsonNode awaitInventoryEvent(UUID orderId, String eventType) {
        Properties properties = new Properties();
        properties.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "inventory-assert-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(Topics.INVENTORY_EVENTS));
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (!orderId.toString().equals(record.key())) {
                        continue;
                    }
                    JsonNode envelope = read(record.value());
                    if (eventType.equals(envelope.get("eventType").asText())) {
                        return envelope;
                    }
                }
            }
        }
        throw new AssertionError("No " + eventType + " for order " + orderId + " arrived on "
                + Topics.INVENTORY_EVENTS);
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        }
        catch (Exception e) {
            throw new AssertionError("Event body is not JSON: " + body, e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void awaitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError("Condition did not become true within " + timeout);
    }

    /**
     * Asserts continuously for a while, which is how "nothing further happened" is checked without
     * trusting a single sample taken at an arbitrary moment.
     *
     * @param duration  how long the assertion must keep holding
     * @param assertion what must stay true
     */
    private static void assertHoldsFor(Duration duration, Runnable assertion) {
        long deadline = System.nanoTime() + duration.toNanos();
        do {
            assertion.run();
            sleep(100);
        }
        while (System.nanoTime() < deadline);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", e);
        }
    }
}
