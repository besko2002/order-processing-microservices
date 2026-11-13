package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.OrderItem;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.common.events.Topics;
import com.example.orders.inventory.domain.OrderReleaseIntentRepository;
import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.saga.InventorySagaTransactions;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Regression for Priority 2: <b>a release must not be forgotten when it arrives before the
 * reservation</b>.
 *
 * <p>{@code OrderCreated} and {@code ReleaseStock} travel on different Kafka topics, so Kafka gives
 * no order between them. Before the fix a release that arrived first was answered with
 * {@code UNKNOWN_ORDER} and remembered nowhere — the {@code OrderCreated} that followed could still
 * reserve stock for the cancelled order, and that late hold had no release left to undo it: a
 * permanent leak. Now every release writes a durable intent row, and an {@code OrderCreated}
 * whose order has one is rejected instead of reserving. These tests drive both topics with real
 * Kafka records and check the database rows and the outbox, the way production sees both.
 */
class InventorySagaReleaseIntentTest extends InventoryIntegrationTest {

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private OrderReleaseIntentRepository releaseIntents;

    @Autowired
    private InventorySagaTransactions inventorySaga;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void clearMessagingTables() {
        outbox.deleteAllInBatch();
        processedEvents.deleteAllInBatch();
        releaseIntents.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ release before reserve

    @Test
    void aReleaseThatArrivesBeforeItsOrderCreatedBlocksAllLaterReservations() throws Exception {
        Product product = givenProduct("SKU-INTENT-1", 10);
        UUID orderId = UUID.randomUUID();
        String reason = "Cancelled by the customer before the stock was reserved";

        // The release arrives first: nothing was ever reserved here (the saga's compensation
        // overtook the reservation, or the order was cancelled in flight). It must be remembered.
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-intent-release", new ReleaseStock(orderId, null, reason, List.of())));
        awaitUntil(Duration.ofSeconds(30), () -> releaseIntents.existsById(orderId));
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).isEmpty();
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
        });

        // The stale OrderCreated now arrives: a reservation would betray the release, so the
        // order is rejected and the stock never moves.
        publish(Topics.ORDER_EVENTS, orderId, orderCreatedEnvelope(orderId, product, 3));

        JsonNode event = awaitInventoryEvent(orderId, "StockRejected");
        assertThat(event.get("payload").get("reason").asText())
                .isEqualTo("The order was cancelled before its stock could be reserved");
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).isEmpty();
            assertThat(outboxTypes(orderId)).doesNotContain("StockReserved");
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
        });
    }

    @Test
    void afterAReserveWasReleasedADuplicateOrderCreatedCannotReserveAgain() throws Exception {
        Product product = givenProduct("SKU-INTENT-2", 10);
        UUID orderId = UUID.randomUUID();
        EventEnvelope<OrderCreated> original = orderCreatedEnvelope(orderId, product, 3);

        // The normal first phase of the saga, then the compensation.
        publish(Topics.ORDER_EVENTS, orderId, original);
        awaitUntil(Duration.ofSeconds(30), () -> !reservations
                .findByOrderIdOrderByCreatedAtAsc(orderId).isEmpty());
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-intent-release", new ReleaseStock(orderId, null, "payment failed", List.of())));
        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().allMatch(row -> row.getStatus() == ReservationStatus.RELEASED));

        // The broker replays the original event (same id): already claimed, no second decision.
        publish(Topics.ORDER_EVENTS, orderId, original);
        // A re-announcing orchestrator would use a fresh id for the same fact: it must hit the
        // intent and be rejected, not reserve what the saga already gave up on.
        publishOrderCreated(orderId, product, 3);

        JsonNode event = awaitInventoryEvent(orderId, "StockRejected");
        assertThat(event.get("payload").get("reason").asText())
                .isEqualTo("The order was cancelled before its stock could be reserved");
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            // Still exactly the released rows, and the still-restored stock.
            assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                    .hasSize(1)
                    .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
            // Exactly one rejection was queued — for the fresh-id duplicate only.
            assertThat(outboxTypes(orderId).stream().filter("StockRejected"::equals).count())
                    .isEqualTo(1);
        });
    }

    @Test
    void duplicateReleasesRestoreTheStockExactlyOnceAndKeepTheFirstIntent() throws Exception {
        Product product = givenProduct("SKU-INTENT-3", 10);
        UUID orderId = reserve(product, 4);

        // Two deliveries with the same event id and two with fresh ids: exactly what repeated
        // commands and replays look like. The released units must not be counted twice, and the
        // first release's intent holds its reason.
        EventEnvelope<ReleaseStock> first =
                EventEnvelope.of("corr-intent-dup-1", new ReleaseStock(orderId, null, "cancelled", List.of()));
        publish(Topics.INVENTORY_COMMANDS, orderId, first);
        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().allMatch(row -> row.getStatus() == ReservationStatus.RELEASED));
        publish(Topics.INVENTORY_COMMANDS, orderId, first);
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-intent-dup-2", new ReleaseStock(orderId, null, "second reason", List.of())));
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-intent-dup-3", new ReleaseStock(orderId, null, "third reason", List.of())));

        awaitUntil(Duration.ofSeconds(30), () -> processedEvents.count() == 4);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
            assertThat(releaseIntents.findById(orderId).orElseThrow().getReason()).isEqualTo("cancelled");
        });
    }

    @Test
    void aCancelledOrderThatWasNeverInThisDatabaseCannotReserveEither() throws Exception {
        // An id from another environment: the release still leaves its intent, so even a replayed
        // OrderCreated from the same foreign environment is powerless (and known orders are
        // untouched — the intent is keyed by order id alone).
        UUID foreignOrder = UUID.randomUUID();
        Product product = givenProduct("SKU-INTENT-4", 10);

        publish(Topics.INVENTORY_COMMANDS, foreignOrder,
                EventEnvelope.of("corr-intent-foreign", new ReleaseStock(foreignOrder, null, "cancelled", List.of())));
        awaitUntil(Duration.ofSeconds(30), () -> releaseIntents.existsById(foreignOrder));
        publish(Topics.ORDER_EVENTS, foreignOrder, orderCreatedEnvelope(foreignOrder, product, 5));

        JsonNode event = awaitInventoryEvent(foreignOrder, "StockRejected");
        assertThat(event.get("payload").get("reason").asText())
                .isEqualTo("The order was cancelled before its stock could be reserved");
        // Nothing of the real catalog was touched.
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
        });
    }

    @Test
    void aCommitStockThatArrivesAfterTheReleaseCannotReviveTheOrder() throws Exception {
        Product product = givenProduct("SKU-INTENT-5", 10);
        UUID orderId = reserve(product, 4);
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-late-commit-release", new ReleaseStock(orderId, null, "cancelled", List.of())));
        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().allMatch(row -> row.getStatus() == ReservationStatus.RELEASED));
        long handled = processedEvents.count();

        // The confirmation that lost the race against the cancellation shows up afterwards.
        publish(Topics.INVENTORY_COMMANDS, orderId,
                EventEnvelope.of("corr-late-commit", new CommitStock(orderId)));

        awaitUntil(Duration.ofSeconds(30), () -> processedEvents.count() == handled + 1);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                    .hasSize(1)
                    .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
            Product after = products.findById(product.getId()).orElseThrow();
            assertThat(after.getAvailableQuantity()).isEqualTo(10);
            assertThat(after.getReservedQuantity()).isZero();
            assertThat(releaseIntents.findById(orderId).orElseThrow().getReason()).isEqualTo("cancelled");
        });
    }

    // ------------------------------------------------------------------ concurrent reserve / release

    @Test
    void aReserveAndItsReleaseRacingForEachOtherSettleOnCommittedData() throws Exception {
        int rounds = 10;
        int stockPerOrder = 6;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                Product product = givenProduct("SKU-INTENT-RACE-%d".formatted(round), stockPerOrder);
                UUID orderId = UUID.randomUUID();
                String reason = "race round " + round;

                // The two saga steps that race in production, driven exactly as the Kafka
                // listeners would drive them: each call is its own transaction, per-order
                // serialized inside the database by the advisory lock.
                CountDownLatch start = new CountDownLatch(1);
                Callable<Boolean> reserveTask = () -> {
                    start.await();
                    inventorySaga.onOrderCreated(UUID.randomUUID(),
                            orderCreatedEventOf(orderId, product, 3, BigDecimal.TEN));
                    return true;
                };
                Callable<Boolean> releaseTask = () -> {
                    start.await();
                    inventorySaga.onReleaseStock(UUID.randomUUID(),
                            new ReleaseStock(orderId, null, reason, List.of()));
                    return true;
                };
                Future<Boolean> reserveFuture = pool.submit(reserveTask);
                Future<Boolean> releaseFuture = pool.submit(releaseTask);
                start.countDown();
                reserveFuture.get(60, TimeUnit.SECONDS);
                releaseFuture.get(60, TimeUnit.SECONDS);

                assertRaceInvariants(orderId, product, reason);
            }
        }
        finally {
            pool.shutdownNow();
        }
    }

    /**
     * Whichever step went first, the pair must end in one of exactly two admissible states and
     * never in limbo: never stock held for a cancelled order, never units restored twice, never a
     * missing intent.
     */
    private void assertRaceInvariants(UUID orderId, Product product, String releaseReason) {
        List<com.example.orders.inventory.domain.StockReservation> rows =
                reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
        Product after = products.findById(product.getId()).orElseThrow();
        List<String> answers = outboxTypes(orderId);

        if (rows.isEmpty()) {
            // The release won: no reservation was granted, a rejection was queued, the intent
            // explains why to everybody who looks later.
            assertThat(answers).contains("StockRejected").doesNotContain("StockReserved");
            assertThat(after.getAvailableQuantity()).isEqualTo(6);
            assertThat(after.getReservedQuantity()).isZero();
        }
        else {
            // The reserve won: the release then ran on committed rows and gave them back.
            assertThat(rows).allSatisfy(row ->
                    assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
            assertThat(answers).contains("StockReserved").doesNotContain("StockRejected");
            assertThat(after.getAvailableQuantity()).isEqualTo(6);
            assertThat(after.getReservedQuantity()).isZero();
        }
        // Both orders of the race end here — the intent must exist either way.
        assertThat(releaseIntents.findById(orderId).orElseThrow().getReason()).isEqualTo(releaseReason);
    }

    // ------------------------------------------------------------------ helpers

    /** @return an order id holding {@code quantity} units, reserved through the live path */
    private UUID reserve(Product product, int quantity) throws Exception {
        UUID orderId = UUID.randomUUID();
        publishOrderCreated(orderId, product, quantity);
        awaitUntil(Duration.ofSeconds(30), () -> reservations.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream().anyMatch(row -> row.getStatus() == ReservationStatus.RESERVED));
        return orderId;
    }

    private void publishOrderCreated(UUID orderId, Product product, int quantity) throws Exception {
        publish(Topics.ORDER_EVENTS, orderId, orderCreatedEnvelope(orderId, product, quantity));
    }

    private static EventEnvelope<OrderCreated> orderCreatedEnvelope(UUID orderId, Product product, int quantity) {
        return EventEnvelope.of("corr-saga-intent",
                orderCreatedEventOf(orderId, product, quantity, BigDecimal.TEN));
    }

    private static OrderCreated orderCreatedEventOf(UUID orderId, Product product, int quantity, BigDecimal unitPrice) {
        OrderItem item = new OrderItem(product.getId(), product.getSku(), quantity, unitPrice);
        return new OrderCreated(orderId, "ORD-INTENT", UUID.randomUUID(),
                unitPrice.multiply(BigDecimal.valueOf(quantity)), "EUR", List.of(item));
    }

    private List<String> outboxTypes(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .map(row -> row.getEventType())
                .toList();
    }

    private void publish(String topic, UUID key, EventEnvelope<?> envelope) throws Exception {
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
                ConsumerConfig.GROUP_ID_CONFIG, "inventory-intent-assert-" + UUID.randomUUID(),
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
