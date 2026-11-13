package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.PaymentFailed;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.StockRejected;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.support.correlation.CorrelationId;
import com.example.orders.support.idempotency.ProcessedEventRepository;
import com.example.orders.support.outbox.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The orchestrator's own behaviour: every transition it makes, every message it emits, and — just as
 * important — every event it refuses to act on.
 *
 * <p>Answers from inventory-service and payment-service are injected as real Kafka records, and the
 * assertions read the {@code orders} table and the outbox. The outbox is the honest place to check
 * what the saga decided to send: it is written in the same transaction as the state change, so a
 * row there means "this service committed to saying this", whereas the topic additionally depends on
 * the relay (covered by {@code OutboxRelayTest}).
 */
class OrderSagaTest extends OrderIntegrationTest {

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void clearDeduplicationLog() {
        processedEvents.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ inventory answers

    @Test
    void stockReservedMovesTheOrderToPaymentPendingAndAsksForTheCharge() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "149.00", 2, "corr-saga-reserved");
        UUID reservationId = UUID.randomUUID();

        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-stock-reserved",
                new StockReserved(orderId, reservationId, List.of())));

        awaitStatus(orderId, OrderStatus.PAYMENT_PENDING);
        Order order = orders.findById(orderId).orElseThrow();
        assertThat(order.getReservationId()).isEqualTo(reservationId);
        assertThat(order.getFailureReason()).isNull();

        OutboxEvent command = awaitOutbox(orderId, "ProcessPayment");
        assertThat(command.getTopic()).isEqualTo(Topics.PAYMENT_COMMANDS);
        JsonNode payload = parse(command.getPayload()).get("payload");
        assertThat(payload.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(payload.get("customerId").asText()).isEqualTo(user.toString());
        assertThat(payload.get("amount").asDouble()).isEqualTo(298.00);
        assertThat(payload.get("currency").asText()).isEqualTo("EUR");

        // And it really leaves the service: key = order id, headers carry the type.
        ConsumerRecord<String, String> record = awaitRecord(Topics.PAYMENT_COMMANDS, orderId, "ProcessPayment");
        assertThat(record.key()).isEqualTo(orderId.toString());
        assertThat(header(record, EventHeaders.CORRELATION_ID)).isEqualTo("corr-stock-reserved");
    }

    @Test
    void stockRejectedRejectsTheOrderWithItsReasonAndAnnouncesIt() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID(), "20.00", 1, "corr-saga-rejected");

        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-stock-rejected",
                new StockRejected(orderId, "Insufficient stock (SKU-1: requested 5, available 2)",
                        List.of("SKU-1"))));

        awaitStatus(orderId, OrderStatus.REJECTED);
        Order order = orders.findById(orderId).orElseThrow();
        assertThat(order.getFailureReason()).contains("Insufficient stock").contains("SKU-1");

        OutboxEvent announced = awaitOutbox(orderId, "OrderRejected");
        assertThat(announced.getTopic()).isEqualTo(Topics.ORDER_EVENTS);
        assertThat(parse(announced.getPayload()).get("payload").get("reason").asText())
                .contains("Insufficient stock");
        // Nothing to compensate: no money and no stock ever moved.
        assertThat(outboxTypes(orderId)).doesNotContain("ReleaseStock", "RefundPayment");
    }

    // ------------------------------------------------------------------ payment answers

    @Test
    void paymentCompletedConfirmsTheOrderAndCommitsTheStock() throws Exception {
        UUID orderId = reservedOrder("corr-saga-confirm");
        UUID paymentId = UUID.randomUUID();

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-payment-done",
                new PaymentCompleted(orderId, paymentId, new BigDecimal("298.00"), "EUR")));

        awaitStatus(orderId, OrderStatus.CONFIRMED);
        assertThat(orders.findById(orderId).orElseThrow().getPaymentId()).isEqualTo(paymentId);

        OutboxEvent confirmed = awaitOutbox(orderId, "OrderConfirmed");
        assertThat(confirmed.getTopic()).isEqualTo(Topics.ORDER_EVENTS);
        OutboxEvent commit = awaitOutbox(orderId, "CommitStock");
        assertThat(commit.getTopic()).isEqualTo(Topics.INVENTORY_COMMANDS);
        assertThat(parse(commit.getPayload()).get("payload").get("orderId").asText())
                .isEqualTo(orderId.toString());
    }

    @Test
    void paymentFailedReleasesTheStockAndCancelsTheOrder() throws Exception {
        UUID orderId = reservedOrder("corr-saga-declined");

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-payment-failed",
                new PaymentFailed(orderId, UUID.randomUUID(), new BigDecimal("298.00"), "EUR",
                        "Payment declined: amount 298.00 EUR exceeds the limit of 100.00")));

        awaitStatus(orderId, OrderStatus.CANCELLED);
        assertThat(orders.findById(orderId).orElseThrow().getFailureReason())
                .contains("Payment declined");

        OutboxEvent release = awaitOutbox(orderId, "ReleaseStock");
        assertThat(release.getTopic()).isEqualTo(Topics.INVENTORY_COMMANDS);
        assertThat(parse(release.getPayload()).get("payload").get("reason").asText())
                .contains("Payment declined");
        OutboxEvent cancelled = awaitOutbox(orderId, "OrderCancelled");
        assertThat(cancelled.getTopic()).isEqualTo(Topics.ORDER_EVENTS);
        // No refund: nothing was ever captured for this order.
        assertThat(outboxTypes(orderId)).doesNotContain("RefundPayment");
    }

    @Test
    void paymentRefundedFinishesACancellationThatWasWaitingForTheMoney() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = confirmedOrder(user, "corr-saga-refund");
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());
        assertThat(orders.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLATION_PENDING);
        UUID paymentId = orders.findById(orderId).orElseThrow().getPaymentId();

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-refunded",
                new PaymentRefunded(orderId, paymentId, new BigDecimal("298.00"), "EUR",
                        "Cancelled by the customer")));

        awaitStatus(orderId, OrderStatus.CANCELLED);
        assertThat(orders.findById(orderId).orElseThrow().getFailureReason())
                .isEqualTo("Cancelled by the customer");
        assertThat(awaitOutbox(orderId, "OrderCancelled")).isNotNull();
    }

    // ------------------------------------------------------------------ duplicates and surprises

    @Test
    void aRedeliveredEventIsIgnoredAndTheStepRunsOnlyOnce() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID(), "50.00", 1, "corr-saga-dup");
        EventEnvelope<StockReserved> reserved = EventEnvelope.of("corr-dup",
                new StockReserved(orderId, UUID.randomUUID(), List.of()));

        publish(Topics.INVENTORY_EVENTS, orderId, reserved);
        awaitStatus(orderId, OrderStatus.PAYMENT_PENDING);
        publish(Topics.INVENTORY_EVENTS, orderId, reserved);

        assertHoldsFor(Duration.ofSeconds(3), () -> {
            assertThat(orders.findById(orderId).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.PAYMENT_PENDING);
            // One charge request, not two: the de-duplication note stopped the second delivery.
            assertThat(countOutbox(orderId, "ProcessPayment")).isEqualTo(1);
        });
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    void aPaymentAnswerForAnOrderThatHasNotReservedStockYetIsIgnored() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID(), "50.00", 1, "corr-saga-early");

        // Out of order: payment cannot have happened before the stock was reserved, so acting on
        // this would confirm an order that is holding nothing.
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-early",
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("50.00"), "EUR")));

        awaitUntil(Duration.ofSeconds(30), () -> processedEvents.count() == 1);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(outboxTypes(orderId)).containsExactly("OrderCreated");
        });
    }

    @Test
    void aLateInventoryAnswerForAFinishedOrderNeverOverwritesItsFinalState() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID(), "50.00", 1, "corr-saga-late");
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reject",
                new StockRejected(orderId, "no stock", List.of("SKU-1"))));
        awaitStatus(orderId, OrderStatus.REJECTED);

        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-late-reserve",
                new StockReserved(orderId, UUID.randomUUID(), List.of())));

        awaitUntil(Duration.ofSeconds(30), () -> processedEvents.count() == 2);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(countOutbox(orderId, "ProcessPayment")).isZero();
        });
    }

    @Test
    void anEventForAnOrderThisServiceDoesNotKnowIsIgnored() {
        UUID unknownOrder = UUID.randomUUID();

        publish(Topics.PAYMENT_EVENTS, unknownOrder, EventEnvelope.of("corr-unknown",
                new PaymentCompleted(unknownOrder, UUID.randomUUID(), new BigDecimal("1.00"), "EUR")));

        awaitUntil(Duration.ofSeconds(30), () -> processedEvents.count() == 1);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(orders.findById(unknownOrder)).isEmpty();
            assertThat(outbox.findByAggregateIdOrderByCreatedAtAsc(unknownOrder)).isEmpty();
        });
    }

    // ------------------------------------------------------------------ customer cancellation

    @Test
    void cancellingAnOrderThatIsStillWaitingForStockReleasesAndCancelsAtOnce() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "50.00", 1, "corr-cancel-pending");

        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("CANCELLED"));

        Order order = orders.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getFailureReason()).isEqualTo("Cancelled by the customer");
        // Released unconditionally: a reservation may exist without this service having heard of it.
        assertThat(outboxTypes(orderId)).contains("ReleaseStock", "OrderCancelled");
        assertThat(outboxTypes(orderId)).doesNotContain("RefundPayment");
    }

    @Test
    void cancellingAnOrderWaitingForPaymentReleasesTheStock() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = reservedOrder(user, "corr-cancel-paying");

        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(outboxTypes(orderId)).contains("ReleaseStock", "OrderCancelled");
    }

    @Test
    void cancellingAConfirmedOrderRefundsFirstAndOnlyThenCallsItselfCancelled() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = confirmedOrder(user, "corr-cancel-confirmed");

        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("CANCELLATION_PENDING"));

        // The customer is not told "cancelled" while the charge is still standing.
        assertThat(orders.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLATION_PENDING);
        assertThat(outboxTypes(orderId)).contains("RefundPayment", "ReleaseStock");
        assertThat(countOutbox(orderId, "OrderCancelled")).isZero();
        JsonNode refund = parse(awaitOutbox(orderId, "RefundPayment").getPayload()).get("payload");
        assertThat(refund.get("paymentId").asText())
                .isEqualTo(orders.findById(orderId).orElseThrow().getPaymentId().toString());
        assertThat(refund.get("amount").asDouble()).isEqualTo(298.00);
    }

    @Test
    void cancellingTwiceIsAConflict() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = confirmedOrder(user, "corr-cancel-twice");
        String token = token(user, "USER");
        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already being cancelled")));
    }

    @Test
    void aRejectedOrderCannotBeCancelled() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "50.00", 1, "corr-cancel-rejected");
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-reject",
                new StockRejected(orderId, "no stock", List.of("SKU-1"))));
        awaitStatus(orderId, OrderStatus.REJECTED);

        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message").value(org.hamcrest.Matchers.containsString("rejected")));
    }

    @Test
    void anAlreadyCancelledOrderCannotBeCancelledAgain() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "50.00", 1, "corr-cancel-cancelled");
        String token = token(user, "USER");
        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already cancelled")));
    }

    @Test
    void somebodyElsesOrderIsNotCancellableAndNotEvenAcknowledged() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID(), "50.00", 1, "corr-cancel-foreign");

        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isNotFound());

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void anAdministratorMayCancelSomebodyElsesOrder() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID(), "50.00", 1, "corr-cancel-admin");

        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancellingWithoutATokenIsRejected() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID(), "50.00", 1, "corr-cancel-anon");

        mvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isUnauthorized());

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    // ------------------------------------------------------------------ correlation id

    @Test
    void theCorrelationIdOfTheRequestTravelsThroughEveryHopOfTheSaga() throws Exception {
        String correlationId = "corr-trace-" + UUID.randomUUID().toString().substring(0, 8);
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "100.00", 1, correlationId);

        // The HTTP request's id is on the first event the service produced...
        assertThat(awaitOutbox(orderId, "OrderCreated").getCorrelationId()).isEqualTo(correlationId);
        ConsumerRecord<String, String> created = awaitRecord(Topics.ORDER_EVENTS, orderId, "OrderCreated");
        assertThat(header(created, EventHeaders.CORRELATION_ID)).isEqualTo(correlationId);

        // ...and a consumer puts the incoming id back into the MDC, so the messages it writes in
        // reaction carry it on to the next service.
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of(correlationId,
                new StockReserved(orderId, UUID.randomUUID(), List.of())));
        awaitStatus(orderId, OrderStatus.PAYMENT_PENDING);

        OutboxEvent command = awaitOutbox(orderId, "ProcessPayment");
        assertThat(command.getCorrelationId()).isEqualTo(correlationId);
        assertThat(parse(command.getPayload()).get("correlationId").asText()).isEqualTo(correlationId);
        ConsumerRecord<String, String> charge = awaitRecord(Topics.PAYMENT_COMMANDS, orderId, "ProcessPayment");
        assertThat(header(charge, EventHeaders.CORRELATION_ID)).isEqualTo(correlationId);
    }

    // ------------------------------------------------------------------ helpers

    private UUID placeOrder(UUID user, String unitPrice, int quantity, String correlationId) throws Exception {
        UUID product = givenCatalogProduct("SKU-SAGA-" + UUID.randomUUID().toString().substring(0, 6),
                "Saga product", unitPrice);
        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .header(CorrelationId.HEADER, correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(parse(body).get("id").asText());
    }

    /** @return an order in {@code PAYMENT_PENDING}, reached through the real saga step */
    private UUID reservedOrder(String correlationId) throws Exception {
        return reservedOrder(UUID.randomUUID(), correlationId);
    }

    private UUID reservedOrder(UUID user, String correlationId) throws Exception {
        UUID orderId = placeOrder(user, "149.00", 2, correlationId);
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of(correlationId,
                new StockReserved(orderId, UUID.randomUUID(), List.of())));
        awaitStatus(orderId, OrderStatus.PAYMENT_PENDING);
        return orderId;
    }

    /** @return a {@code CONFIRMED} order with a payment id, reached through the real saga steps */
    private UUID confirmedOrder(UUID user, String correlationId) throws Exception {
        UUID orderId = reservedOrder(user, correlationId);
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of(correlationId,
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("298.00"), "EUR")));
        awaitStatus(orderId, OrderStatus.CONFIRMED);
        return orderId;
    }

    private void awaitStatus(UUID orderId, OrderStatus expected) {
        awaitUntil(Duration.ofSeconds(45), () -> orders.findById(orderId)
                .map(order -> order.getStatus() == expected)
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

    private ConsumerRecord<String, String> awaitRecord(String topic, UUID orderId, String eventType) {
        Properties properties = new Properties();
        properties.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "order-saga-assert-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (orderId.toString().equals(record.key())
                            && eventType.equals(header(record, EventHeaders.EVENT_TYPE))) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No " + eventType + " for order " + orderId + " arrived on " + topic);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
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
