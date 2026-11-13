package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import com.example.orders.order.saga.OrderSaga;
import com.example.orders.support.correlation.CorrelationId;
import com.example.orders.support.idempotency.ProcessedEventRepository;
import com.example.orders.support.outbox.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Regression for Priority 1: <b>a payment that completes after the saga gave up on the order</b>.
 *
 * <p>Before the fix, a {@code PaymentCompleted} arriving for a cancelled (or timed-out, or
 * cancelling) order was only logged and dropped — its {@code paymentId} was never learned by the
 * saga, no {@code RefundPayment} was queued, and the payment provider kept the charge authorized
 * against a cancelled order. These tests drive the full path with real Kafka records and assert on
 * the committed {@code orders} row and the outbox, which is the honest evidence: state and messages
 * commit together or not at all.
 *
 * <p>The invariants every scenario ends with:
 * <ul>
 *   <li>the charge is refunded exactly once ({@code RefundPayment} queue count and
 *       {@code refund_status} agree),</li>
 *   <li>a cancelled order is never re-confirmed: no {@code OrderConfirmed}, no {@code CommitStock},</li>
 *   <li>{@code OrderCancelled} is announced exactly once, whatever arrives afterwards,</li>
 *   <li>duplicate deliveries — same event id (Kafka redelivery) <em>and</em> different event ids
 *       (replayed rows, re-announcing payment-service) — queue no second command and apply no
 *       second completion.</li>
 * </ul>
 */
class OrderSagaLatePaymentTest extends OrderIntegrationTest {

    @Autowired
    private OrderSaga saga;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void clearDeduplicationLog() {
        processedEvents.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ the late payment itself

    @Test
    void aPaymentThatCompletesAfterTheCustomerCancelledIsRefundedAndTracked() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "119.00", 1, "corr-late-cancel");

        // Cancel while the saga is still young, before any payment answer exists.
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.NONE);
        assertThat(outboxTypes(orderId)).contains("ReleaseStock", "OrderCancelled")
                .doesNotContain("RefundPayment");

        UUID paymentId = UUID.randomUUID();
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-payment",
                new PaymentCompleted(orderId, paymentId, new BigDecimal("119.00"), "EUR")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REQUESTED);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order(orderId).getPaymentId()).isEqualTo(paymentId);

        // The refund is commanded and tracked; the cancelled order is not reconfirmed and its
        // stock is not committed.
        JsonNode refund = parse(awaitOutbox(orderId, "RefundPayment").getPayload()).get("payload");
        assertThat(refund.get("paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(refund.get("amount").asDouble()).isEqualTo(119.00);
        assertThat(outboxTypes(orderId)).doesNotContain("OrderConfirmed", "CommitStock");
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);

        // The refund comes back: the flag closes, the order stays cancelled, nothing is announced
        // a second time.
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-refunded",
                new PaymentRefunded(orderId, paymentId, new BigDecimal("119.00"), "EUR",
                        "The order was already cancelled when its payment completed")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
    }

    @Test
    void aPaymentThatCompletesAfterTheTimeoutSweepIsRefundedAndTracked() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID(), "119.00", 1, "corr-late-timeout");

        // The sweep cancelled this order with compensation; the charge has not been heard of yet.
        assertThat(saga.timeOut(orderId, "Test: the sweep gave up")).isTrue();
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(countOutbox(orderId, "RefundPayment")).isZero();

        UUID paymentId = UUID.randomUUID();
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-timeout-payed",
                new PaymentCompleted(orderId, paymentId, new BigDecimal("119.00"), "EUR")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REQUESTED);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
        assertThat(countOutbox(orderId, "CommitStock")).isZero();

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-timeout-refunded",
                new PaymentRefunded(orderId, paymentId, new BigDecimal("119.00"), "EUR", "Timed out")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        // The sweep already announced the cancellation; the refund's arrival must not repeat it.
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void aPaymentConfirmedBeforeTheCancellationWaitsForItsRefundAndThenFinishes() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = confirmedOrder(user, "corr-pay-then-cancel");

        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
        // The cancellation is announced only when the money is really back.
        assertThat(countOutbox(orderId, "OrderCancelled")).isZero();

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-pay-then-cancel-refunded",
                new PaymentRefunded(orderId, order(orderId).getPaymentId(),
                        new BigDecimal("298.00"), "EUR", "Cancelled by the customer")));

        awaitStatus(orderId, OrderStatus.CANCELLED);
        assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REFUNDED);
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ duplicates

    @Test
    void duplicateLatePaymentsQueueExactlyOneRefund() throws Exception {
        UUID orderId = cancelledInFlightOrder("corr-late-dupes");
        UUID paymentId = UUID.randomUUID();

        // Four deliveries of the same fact: the same event id twice (a Kafka redelivery), then two
        // distinct event ids (a re-announcing payment-service uses fresh ones). Only the first
        // delivery may queue a refund.
        EventEnvelope<PaymentCompleted> first = EventEnvelope.of("corr-late-dupes-1",
                new PaymentCompleted(orderId, paymentId, new BigDecimal("119.00"), "EUR"));
        publish(Topics.PAYMENT_EVENTS, orderId, first);
        publish(Topics.PAYMENT_EVENTS, orderId, first);
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-dupes-2",
                new PaymentCompleted(orderId, paymentId, new BigDecimal("119.00"), "EUR")));
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-dupes-3",
                new PaymentCompleted(orderId, paymentId, new BigDecimal("119.00"), "EUR")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REQUESTED);
        assertHoldsFor(Duration.ofSeconds(3), () -> {
            assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        });
        // Three distinct event ids were claimed; the redelivery of the first added no second note.
        assertThat(processedEvents.count()).isEqualTo(3);

        // One refund completion settles the book, however many announcements arrive.
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-dupes-r1",
                new PaymentRefunded(orderId, paymentId, new BigDecimal("119.00"), "EUR", "cancelled")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-dupes-r2",
                new PaymentRefunded(orderId, paymentId, new BigDecimal("119.00"), "EUR", "cancelled")));
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-late-dupes-r3",
                new PaymentRefunded(orderId, paymentId, new BigDecimal("119.00"), "EUR", "cancelled")));

        awaitUntil(Duration.ofSeconds(45), () -> processedEvents.count() == 6);
        assertHoldsFor(Duration.ofSeconds(3), () -> {
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REFUNDED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    @Test
    void aPaymentRefundedForAnOrderThatNeverHadATrackedRefundIsHarmless() throws Exception {
        // Order cancelled by the customer while nothing was charged: policy never requested a
        // refund. An eventual (defensive) PaymentRefunded re-announcement must not mutate
        // anything, in particular neither open a refund book nor re-announce the cancellation.
        UUID orderId = cancelledInFlightOrder("corr-norefund");

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-norefund-r",
                new PaymentRefunded(orderId, UUID.randomUUID(), new BigDecimal("50.00"), "EUR", "bogus")));

        awaitUntil(Duration.ofSeconds(45), () -> processedEvents.count() == 1);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.NONE);
            assertThat(order(orderId).getPaymentId()).isNull();
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------ interrupted refund

    @Test
    void anInterruptedRefundIsRetriedByTheSweepAndThenCompletedExactlyOnce() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = confirmedOrder(user, "corr-refund-retry");

        // The refund is commanded (the customer cancelled a confirmed order) but the answer never
        // comes: the command was lost. The sweep is the one deliberate retry path.
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
        OffsetDateTime firstAttempt = order(orderId).getRefundRetryAt();

        // The order is not stuck — it is waiting for its money (this is what CANCELLATION_PENDING
        // is for) — so the saga timeout has nothing to do with it: no re-command on the timeout's
        // clock, no cancellation (that would strand the refund, final states are not swept, and
        // tell the customer "cancelled" while the charge still stands).
        assertThat(saga.timeOut(orderId, "Test: the sweep gave up on a waiting order")).isFalse();
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
        // The deliberate retry path is the refund recovery, and it keeps to the retry interval: a
        // window that has not opened yet (the first command is moments old) re-queues nothing...
        assertThat(saga.recoverOutstandingRefund(orderId, "Test: too early",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10))).isFalse();
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(1);
        // ...and once the window opens (the cutoff a sweep one retry interval later would compute
        // reaches the first attempt's stamp) the lost command is re-commanded.
        assertThat(saga.recoverOutstandingRefund(orderId, "Test: the refund sweep retry", firstAttempt)).isTrue();
        // The waiting state is not consumed by the retry either.
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        // Re-commanded in the same transaction that booked the retry attempt.
        assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(2);
        assertThat(order(orderId).getRefundRetryAt()).isAfter(firstAttempt);
        // Settling the cancellation is the refund's completion, not the timeout's business:
        // no announcement of a cancellation whose money is still pending.
        assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(0);

        UUID paymentId = order(orderId).getPaymentId();
        // The refund completes once and may be re-announced by a duplicate command; only the flag
        // may be closed once.
        BigDecimal refunded = totalOf(orderId);
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-refund-retry-1",
                new PaymentRefunded(orderId, paymentId, refunded, "EUR", "cancelled")));
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-refund-retry-2",
                new PaymentRefunded(orderId, paymentId, refunded, "EUR", "cancelled")));

        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        // Both refund announcements were consumed: the two setup events plus the two answers.
        awaitUntil(Duration.ofSeconds(45), () -> processedEvents.count() == 4);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------ concurrent cancel / pay

    @Test
    void concurrentCancellationAndPaymentCompletionProduceExactlyOneRefund() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int rounds = 8;
            for (int round = 0; round < rounds; round++) {
                UUID user = UUID.randomUUID();
                String correlationId = "corr-concurrent-" + round;
                // Get the order to PAYMENT_PENDING first: only from there is a completed charge a
                // real scenario (payment-service is asked to charge exactly while stock is held).
                UUID orderId = reservedOrder(user, correlationId);
                UUID paymentId = UUID.randomUUID();
                BigDecimal total = order(orderId).getTotalAmount();
                PaymentCompleted payment = new PaymentCompleted(orderId, paymentId, total, "EUR");

                CountDownLatch start = new CountDownLatch(1);
                // One task runs the customer's cancellation through the HTTP path, the other drives
                // the payment answer through the saga's entry point (what a Kafka record would do).
                // Both hit the same order row; the optimistic-locking version decides a winner, the
                // loser retries on fresh data, and the guards settle the outcome.
                Callable<Boolean> cancelTask = () -> {
                    start.await();
                    mvc.perform(post("/api/orders/{id}/cancel", orderId)
                                    .header("Authorization", "Bearer " + token(user, "USER")))
                            .andExpect(status().isOk());
                    return true;
                };
                Callable<Boolean> payTask = () -> {
                    start.await();
                    saga.paymentCompleted(UUID.randomUUID(), payment);
                    return true;
                };
                Future<Boolean> cancelFuture = pool.submit(cancelTask);
                Future<Boolean> payFuture = pool.submit(payTask);
                start.countDown();
                cancelFuture.get(60, TimeUnit.SECONDS);
                payFuture.get(60, TimeUnit.SECONDS);

                assertRaceInvariants(orderId, paymentId);
            }
        }
        finally {
            pool.shutdownNow();
        }
    }

    /** Settles the refund of an order that just went through a cancellation/payment race. */
    private void assertRaceInvariants(UUID orderId, UUID paymentId) {
        Order order = orders.findById(orderId).orElseThrow();
        List<String> types = outboxTypes(orderId);
        long ordersConfirmed = types.stream().filter("OrderConfirmed"::equals).count();
        long refundsQueued = types.stream().filter("RefundPayment"::equals).count();
        long ordersCancelled = types.stream().filter("OrderCancelled"::equals).count();
        long commitsQueued = types.stream().filter("CommitStock"::equals).count();

        // Both interleavings converge on the same tracked-refund outcome, whichever of the two
        // transactions won the row: the charge is refunded once, never re-declared, and the
        // cancelled order is neither confirmed nor committed.
        assertThat(refundsQueued)
                .as("order %s: types %s", orderId, types)
                .isEqualTo(1);
        assertThat(ordersConfirmed).isLessThanOrEqualTo(1);
        assertThat(ordersCancelled).isLessThanOrEqualTo(1);
        assertThat(order.getStatus()).isIn(OrderStatus.CANCELLATION_PENDING, OrderStatus.CANCELLED);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);
        assertThat(order.getRefundStatus()).isIn(RefundStatus.REQUESTED, RefundStatus.REFUNDED);

        if (ordersConfirmed > 0) {
            assertThat(commitsQueued).isEqualTo(1);
        }
        else {
            // The cancel won before the charge was applied: no commit for stock it released.
            assertThat(commitsQueued).isZero();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        // Settle the outstanding refund so the next round starts clean.
        if (order.getStatus() == OrderStatus.CANCELLATION_PENDING) {
            publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-race-refunded-" + orderId,
                    new PaymentRefunded(orderId, paymentId, totalOf(orderId), "EUR", "cancelled")));
            awaitUntil(Duration.ofSeconds(45), () -> orders.findById(orderId)
                    .map(found -> found.getRefundStatus() == RefundStatus.REFUNDED
                            && found.getStatus() == OrderStatus.CANCELLED)
                    .orElse(false));
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    private BigDecimal totalOf(UUID orderId) {
        return orders.findById(orderId).orElseThrow().getTotalAmount();
    }

    private Order order(UUID orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    /** @return an order cancelled while still waiting for infrastructure answers */
    private UUID cancelledInFlightOrder(String correlationId) throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, "119.00", 1, correlationId);
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
        awaitStatus(orderId, OrderStatus.CANCELLED);
        return orderId;
    }

    private UUID placeOrder(UUID user, String unitPrice, int quantity, String correlationId) throws Exception {
        UUID product = givenCatalogProduct("SKU-LATE-" + UUID.randomUUID().toString().substring(0, 6),
                "Late payment product", unitPrice);
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
    private UUID reservedOrder(UUID user, String correlationId) throws Exception {
        UUID orderId = placeOrder(user, "119.00", 2, correlationId);
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of(correlationId,
                new StockReserved(orderId, UUID.randomUUID(), List.of())));
        awaitStatus(orderId, OrderStatus.PAYMENT_PENDING);
        return orderId;
    }

    /** @return a {@code CONFIRMED} order with a payment id, reached through the real saga steps */
    private UUID confirmedOrder(UUID user, String correlationId) throws Exception {
        UUID orderId = reservedOrder(user, correlationId);
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of(correlationId,
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("238.00"), "EUR")));
        awaitStatus(orderId, OrderStatus.CONFIRMED);
        return orderId;
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
