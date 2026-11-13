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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Regression for the two Phase-1 review defects around refund tracking.
 *
 * <p>One: {@code onPaymentRefunded} ignored the payment-id guard's answer, so a
 * {@code PaymentRefunded} naming <em>another</em> order's payment could settle the tracked refund
 * of this one — flipping the flag to {@code REFUNDED}, finalising the cancellation and publishing
 * {@code OrderCancelled} on the strength of a foreign event.
 *
 * <p>Two: an outstanding refund became unrecoverable once its order was cancelled. The timeout's
 * first retry also cancelled a {@code CANCELLATION_PENDING} order (which silenced the customer's
 * waiting state and closed the only state the retry could still run in), the scan that feeds the
 * sweep never selected {@code CANCELLED} orders, and its timeOut early-returned for them. The fix
 * gives the refund its own recovery: a deliberate retry interval, persisted as
 * {@code refund_retry_at} and booked atomically with every re-queued command, driving a second
 * sweep whose candidates are {@code CANCELLED}/{@code CANCELLATION_PENDING} orders with a
 * {@code REQUESTED} refund.
 *
 * <p>The sweep runs every 300 ms and the retry interval is one second, so cadence, throttling and
 * settlement are observed through the real scheduled path in seconds, with no sleeps: growth
 * assertions poll with {@code awaitUntil}, stability assertions are pinned with
 * {@code assertHoldsFor} (several sweep ticks long). Nothing else consumes the refund commands, so
 * every re-queued {@code RefundPayment} stays in the outbox as the evidence of an attempt.
 */
@TestPropertySource(properties = {
        "orders.saga.refund-retry-interval=PT1S",
        "orders.saga.sweep-interval-ms=300"
})
// The context is torn down with the class: its fast sweeper would otherwise keep running on the
// shared database and re-command the refunds of every test class that comes after it.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderRefundRecoveryTest extends OrderIntegrationTest {

    @Autowired
    private OrderSaga saga;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void clearDeduplicationLog() {
        processedEvents.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ the payment-id guard

    @Test
    void aForeignRefundAnswerCannotSettleTheTrackedRefundOfACancellingOrder() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancellationPendingOrder(user, "corr-foreign-pending");
        UUID trackedPayment = order(orderId).getPaymentId();
        BigDecimal total = order(orderId).getTotalAmount();

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-foreign-pending-refund",
                new PaymentRefunded(orderId, UUID.randomUUID(), total, "EUR",
                        "some other order's refund")));

        // The foreign answer is consumed and dropped: no flag flip, no cancellation, no
        // announcement, and the tracked payment id is untouched. Re-queues the sweep may make in
        // the meantime always name the tracked payment, never the foreign one.
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
            assertThat(order(orderId).getPaymentId()).isEqualTo(trackedPayment);
            assertThat(countOutbox(orderId, "OrderCancelled")).isZero();
            everyRefundNamesPayment(orderId, trackedPayment);
        });

        // The matching answer settles the tracked refund into the normal completion.
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-foreign-pending-matched",
                new PaymentRefunded(orderId, trackedPayment, total, "EUR", "refund")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
            everyRefundNamesPayment(orderId, trackedPayment);
        });
    }

    @Test
    void aForeignRefundAnswerDoesNothingToACancelledOrderOwingRefund() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancelledOrderLateCharged(user, "corr-foreign-cancelled");
        UUID trackedPayment = order(orderId).getPaymentId();
        BigDecimal total = order(orderId).getTotalAmount();

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-foreign-cancelled-refund",
                new PaymentRefunded(orderId, UUID.randomUUID(), total, "EUR",
                        "some other order's refund")));

        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
            assertThat(order(orderId).getPaymentId()).isEqualTo(trackedPayment);
            // The cancellation was announced once when the customer cancelled; a foreign refund
            // answer must not repeat it, and the recovery must treat the refund as still owed.
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
            everyRefundNamesPayment(orderId, trackedPayment);
        });

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-foreign-cancelled-matched",
                new PaymentRefunded(orderId, trackedPayment, total, "EUR", "refund")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------ the recovery sweep

    @Test
    void theScheduledSweepRecommandsALostRefundForACancelledOrder() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancelledOrderLateCharged(user, "corr-recovered-refund");
        UUID trackedPayment = order(orderId).getPaymentId();
        BigDecimal total = order(orderId).getTotalAmount();

        // The first command was published into a Kafka topic nothing answers; one retry interval
        // later the scheduled scan hands the order to the recovery, which re-queues it.
        awaitUntil(Duration.ofSeconds(45), () -> countOutbox(orderId, "RefundPayment") >= 2);

        JsonNode lastRefund = parse(lastOutboxRow(orderId, "RefundPayment").getPayload()).get("payload");
        assertThat(lastRefund.get("paymentId").asText()).isEqualTo(trackedPayment.toString());
        assertThat(lastRefund.get("amount").asDouble()).isEqualTo(total.doubleValue());
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
            // Recovery retries the money, not the cancellation: no second announcement.
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
            everyRefundNamesPayment(orderId, trackedPayment);
        });
    }

    @Test
    void aCancellingOrderSurvivesRepeatedlyInterruptedRefundAttempts() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancellationPendingOrder(user, "corr-interrupted-refunds");
        UUID trackedPayment = order(orderId).getPaymentId();
        BigDecimal total = order(orderId).getTotalAmount();

        // More than one interrupted attempt: the original command plus two re-commands from the
        // sweep, none of which is allowed to consume the waiting state or announce anything.
        awaitUntil(Duration.ofSeconds(45), () -> countOutbox(orderId, "RefundPayment") >= 3);
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isZero();
            everyRefundNamesPayment(orderId, trackedPayment);
        });

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-interrupted-refunds-matched",
                new PaymentRefunded(orderId, trackedPayment, total, "EUR", "refund")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            // The announcement belongs to the refund's completion, exactly once.
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    @Test
    void aSettledRefundStopsTheRecoverySweep() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancelledOrderLateCharged(user, "corr-settled-refund");
        UUID trackedPayment = order(orderId).getPaymentId();
        BigDecimal total = order(orderId).getTotalAmount();

        // Recovery is alive: the outstanding refund gets re-commanded at least once.
        awaitUntil(Duration.ofSeconds(45), () -> countOutbox(orderId, "RefundPayment") >= 2);

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-settled-refunded",
                new PaymentRefunded(orderId, trackedPayment, total, "EUR", "refund")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REFUNDED);

        long commandsWhenSettled = countOutbox(orderId, "RefundPayment");
        // Three seconds ≈ ten sweep ticks and three retry windows: a settled refund must not be
        // re-commanded again, and nothing else about the finished cancellation may move.
        assertHoldsFor(Duration.ofSeconds(3), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REFUNDED);
            assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(commandsWhenSettled);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------ retry safety

    @Test
    void recoveryIsBookedAgainstTheRetryWindowAndNeverDuplicatesEffects() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = cancelledOrderLateCharged(user, "corr-throttled-recovery");
        UUID trackedPayment = order(orderId).getPaymentId();

        // Direct recovery, sharing the sweep's entry point and its window semantics: dueBefore is
        // the refund's own last-attempt stamp, so the refund is due exactly now.
        OffsetDateTime lastAttempt = order(orderId).getRefundRetryAt();
        assertThat(lastAttempt).isNotNull();

        // Concurrent attempts cannot book twice: the window's winner commits the booking, the
        // optimistic-locking loser retries, reads the fresh timestamp and stands down.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int requeues;
        try {
            Callable<Boolean> attempt = () -> saga.recoverOutstandingRefund(
                    orderId, "Test: concurrent recovery", lastAttempt);
            Future<Boolean> first = pool.submit(attempt);
            Future<Boolean> second = pool.submit(attempt);
            int trues = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(trues).as("exactly one attempt may win a window").isEqualTo(1);

            // A repeat inside the same, freshly booked window is throttled.
            assertThat(saga.recoverOutstandingRefund(
                    orderId, "Test: immediate repeat", lastAttempt)).isFalse();

            assertThat(countOutbox(orderId, "RefundPayment")).isEqualTo(2);
            requeues = 2;
        }
        finally {
            pool.shutdownNow();
        }
        // ...and every re-queue names the tracked payment: duplicates are command-level noise that
        // payment-service absorbs idempotently, never a second money effect.
        everyRefundNamesPayment(orderId, trackedPayment);

        // The next window is due one interval later: the scheduled sweep must pick it up again.
        awaitUntil(Duration.ofSeconds(45), () -> countOutbox(orderId, "RefundPayment") > requeues);
        assertHoldsFor(Duration.ofSeconds(1), () -> {
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(orderId).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
            assertThat(countOutbox(orderId, "OrderCancelled")).isEqualTo(1);
        });
    }

    @Test
    void ordersWithNoOutstandingRefundAreNotTouchedByTheRecoverySweep() throws Exception {
        // A confirmed order paid normally has no open refund and must not hear from the sweep.
        UUID user = UUID.randomUUID();
        UUID confirmedId = confirmedOrder(user, "corr-untouched-confirmed");
        List<String> confirmedTypes = outboxTypes(confirmedId);
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(outboxTypes(confirmedId)).isEqualTo(confirmedTypes);
            assertThat(order(confirmedId).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order(confirmedId).getRefundStatus()).isEqualTo(RefundStatus.NONE);
        });

        // A cancelled order that was never paid owes nothing either: cancellation compensation
        // stays exactly as it was, no refund command appears, no flag is flipped.
        UUID user2 = UUID.randomUUID();
        UUID cancelledId = placeOrder(user2, "119.00", 2, "corr-untouched-cancelled");
        cancelByCustomer(cancelledId, user2);
        awaitStatus(cancelledId, OrderStatus.CANCELLED);
        List<String> cancelledTypes = outboxTypes(cancelledId);
        assertThat(cancelledTypes).doesNotContain("RefundPayment");
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(outboxTypes(cancelledId)).isEqualTo(cancelledTypes);
            assertThat(order(cancelledId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(cancelledId).getRefundStatus()).isEqualTo(RefundStatus.NONE);
        });
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * @return an order cancelled by its owner while in flight (already announced, refund not yet
     *         owed), then charged late — so the row ends {@code CANCELLED}, tracking one
     *         {@code REQUESTED} refund with its first command queued
     */
    private UUID cancelledOrderLateCharged(UUID user, String correlationId) throws Exception {
        UUID orderId = placeOrder(user, "199.00", 1, correlationId);
        cancelByCustomer(orderId, user);
        awaitStatus(orderId, OrderStatus.CANCELLED);

        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of(correlationId + "-late-pay",
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("199.00"), "EUR")));
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REQUESTED);
        return orderId;
    }

    /**
     * @return a {@code CONFIRMED} order cancelled by its owner while the charge stands: the row
     *         waits in {@code CANCELLATION_PENDING} with the refund {@code REQUESTED}
     */
    private UUID cancellationPendingOrder(UUID user, String correlationId) throws Exception {
        UUID orderId = confirmedOrder(user, correlationId);
        cancelByCustomer(orderId, user);
        awaitStatus(orderId, OrderStatus.CANCELLATION_PENDING);
        awaitUntil(Duration.ofSeconds(45), () -> order(orderId).getRefundStatus() == RefundStatus.REQUESTED);
        return orderId;
    }

    private void cancelByCustomer(UUID orderId, UUID user) throws Exception {
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk());
    }

    private UUID confirmedOrder(UUID user, String correlationId) throws Exception {
        UUID orderId = placeOrder(user, "119.00", 2, correlationId);
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of(correlationId,
                new StockReserved(orderId, UUID.randomUUID(), List.of())));
        awaitStatus(orderId, OrderStatus.PAYMENT_PENDING);
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of(correlationId,
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("238.00"), "EUR")));
        awaitStatus(orderId, OrderStatus.CONFIRMED);
        return orderId;
    }

    private UUID placeOrder(UUID user, String unitPrice, int quantity, String correlationId) throws Exception {
        UUID product = givenCatalogProduct("SKU-RECOVERY-" + UUID.randomUUID().toString().substring(0, 6),
                "Recovery product", unitPrice);
        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .header(CorrelationId.HEADER, correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(parse(body).get("id").asText());
    }

    // ------------------------------------------------------------------ assertions & infra

    /** @param paymentId every queued {@code RefundPayment} for this order must name it */
    private void everyRefundNamesPayment(UUID orderId, UUID paymentId) {
        for (OutboxEvent row : outbox.findByAggregateIdOrderByCreatedAtAsc(orderId)) {
            if ("RefundPayment".equals(row.getEventType())) {
                assertThat(quietParse(row.getPayload()).get("payload").get("paymentId").asText())
                        .isEqualTo(paymentId.toString());
            }
        }
    }

    private JsonNode quietParse(String payload) {
        try {
            return parse(payload);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Outbox payload is not JSON", e);
        }
    }

    private OutboxEvent lastOutboxRow(UUID orderId, String eventType) {
        List<OutboxEvent> rows = outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> eventType.equals(row.getEventType()))
                .toList();
        assertThat(rows).isNotEmpty();
        return rows.get(rows.size() - 1);
    }

    private List<OutboxEvent> findOutbox(UUID orderId, String eventType) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> eventType.equals(row.getEventType()))
                .toList();
    }

    private long countOutbox(UUID orderId, String eventType) {
        return findOutbox(orderId, eventType).size();
    }

    private List<String> outboxTypes(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId)
                .stream().map(OutboxEvent::getEventType).toList();
    }

    private Order order(UUID orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    private void awaitStatus(UUID orderId, OrderStatus expected) {
        awaitUntil(Duration.ofSeconds(45), () -> orders.findById(orderId)
                .map(found -> found.getStatus() == expected)
                .orElse(false));
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
}
