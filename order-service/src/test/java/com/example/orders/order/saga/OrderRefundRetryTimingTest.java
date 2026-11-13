package com.example.orders.order.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.order.OrderIntegrationTest;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import com.example.orders.support.outbox.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Regression for the shared refund-retry timing: the saga <em>timeout</em> and the refund
 * <em>retry interval</em> are two independent clocks, and with the retry interval the longer one a
 * refund must still be re-commanded only when {@code refund_retry_at} says so — never early
 * because the order happens to be older than the timeout.
 *
 * <p>What this pins, against a real Postgres (the candidate selection is a database query, and
 * its {@code LIMIT 50 ... ORDER BY updated_at} is half the story):
 * <ul>
 *   <li>before the retry is due, neither scan re-queues the refund — not the timeout scan, not the
 *       recovery scan, not a direct {@code timeOut};</li>
 *   <li>once it is due, exactly one command is queued, together with the new retry stamp, and
 *       neither the order's state and reason nor its payment identity moves; duplicate and
 *       concurrent recoveries inside the freshly booked window are throttled;</li>
 *   <li>a settled refund ({@code REFUNDED}) is never retried again;</li>
 *   <li>refunds that are only waiting for their money never fill the timeout scan's capped batch,
 *       so genuinely stuck orders are still compensated and cancelled — ordinary timeout
 *       compensation is unchanged.</li>
 * </ul>
 *
 * <p>Deterministic by construction: no sweeper thread of this context runs (the bean is switched
 * off; the test builds the same {@link OrderSagaTimeoutSweeper} by hand with the context's own
 * timeout and retry interval and calls the two scans itself), and "time passing" is the fixture's
 * persisted {@code updated_at}/{@code refund_retry_at} aged with SQL — nothing sleeps. (The sweeper
 * is built by hand rather than fetched from the context also because its scan methods are
 * {@code @Transactional(readOnly = true)}: through the Spring proxy they would wrap the saga's
 * writes in a read-only transaction, which the scheduled path avoids by calling them from
 * {@code sweep()} on the bare bean.)
 *
 * <p>The numbers are production's relationship (timeout PT2M, retry interval longer, e.g. PT10M)
 * scaled down to seconds, and chosen so that the <em>default</em> context's live sweeper (15 s tick,
 * 2 min timeout, 30 s retry interval), which a shared-JVM run may keep alive on the same database,
 * has no business with these rows either: a "not due" refund is younger than 30 s, a "due" one is
 * older than this context's interval but still younger than 30 s, and nothing here is older than
 * its 2 min timeout.
 */
@TestPropertySource(properties = {
        "orders.saga.timeout=PT5S",
        "orders.saga.refund-retry-interval=PT20S",
        // No scheduled sweep in this context: the test drives both scans by hand.
        "orders.saga.timeout-sweeper-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderRefundRetryTimingTest extends OrderIntegrationTest {

    private static final String REFUND = "RefundPayment";

    /** Past the order timeout (5 s), short of the retry interval (20 s): stuck, but not due. */
    private static final Duration NOT_DUE_AGE = Duration.ofSeconds(12);

    /** Past the retry interval (20 s), still short of the default context's 30 s. */
    private static final Duration DUE_AGE = Duration.ofSeconds(25);

    /** Past the order timeout only: an ordinary stuck order, younger than every aged refund. */
    private static final Duration STUCK_AGE = Duration.ofSeconds(8);

    @Value("${orders.saga.timeout}")
    private Duration timeout;

    @Value("${orders.saga.refund-retry-interval}")
    private Duration refundRetryInterval;

    @Autowired
    private OrderSaga saga;

    @Autowired
    private JdbcTemplate jdbc;

    private OrderSagaTimeoutSweeper sweeper;

    @BeforeEach
    void buildSweeper() {
        // The regression's precondition, stated once: the retry interval is the longer clock, and
        // the fixtures' ages sit where each test needs them to.
        assertThat(refundRetryInterval).isGreaterThan(timeout);
        assertThat(NOT_DUE_AGE).isGreaterThan(timeout).isLessThan(refundRetryInterval);
        assertThat(DUE_AGE).isGreaterThan(refundRetryInterval);
        assertThat(STUCK_AGE).isGreaterThan(timeout).isLessThan(NOT_DUE_AGE);
        sweeper = new OrderSagaTimeoutSweeper(orders, saga, timeout, refundRetryInterval);
    }

    // ------------------------------------------------------------------ before the retry is due

    @Test
    void aRefundThatIsNotDueYetIsRequeuedByNeitherScan() {
        UUID waiting = refundOwingOrder(OrderStatus.CANCELLATION_PENDING);
        UUID cancelled = refundOwingOrder(OrderStatus.CANCELLED);
        age(NOT_DUE_AGE, waiting, cancelled);
        Book waitingBefore = book(waiting);
        Book cancelledBefore = book(cancelled);
        // The fixture is what the bug needs: the rows are older than the order timeout...
        assertThat(waitingBefore.updatedAt()).isBefore(now().minus(timeout));
        // ...while their refund was last commanded well inside the retry interval.
        assertThat(waitingBefore.retryAt()).isAfter(now().minus(refundRetryInterval));

        assertThat(sweeper.cancelTimedOut()).as("timeout scan").isZero();
        assertThat(sweeper.recoverOutstandingRefunds()).as("refund recovery scan").isZero();
        // The timeout itself, called directly (what the scan used to do per candidate), is no retry
        // driver either — for a waiting order or a cancelled one.
        assertThat(saga.timeOut(waiting, "Test: direct timeout")).isFalse();
        assertThat(saga.timeOut(cancelled, "Test: direct timeout")).isFalse();
        // And again, in the other order, as repeated ticks do.
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        assertThat(sweeper.cancelTimedOut()).isZero();

        // Nothing moved: not the state, the reason, the payment, the retry stamp, the version (no
        // write at all), and no command or announcement was queued.
        assertThat(book(waiting)).isEqualTo(waitingBefore);
        assertThat(book(cancelled)).isEqualTo(cancelledBefore);
        assertThat(outbox.findAll()).isEmpty();
    }

    // ------------------------------------------------------------------ once it is due

    @Test
    void aDueRefundIsRequeuedExactlyOnceAndThenThrottled() {
        UUID waiting = refundOwingOrder(OrderStatus.CANCELLATION_PENDING);
        UUID cancelled = refundOwingOrder(OrderStatus.CANCELLED);
        age(DUE_AGE, waiting, cancelled);
        Book waitingBefore = book(waiting);
        Book cancelledBefore = book(cancelled);
        OffsetDateTime beforeSweep = now();

        // The timeout scan never re-queues a refund — not even a due one; the order is not stuck.
        assertThat(sweeper.cancelTimedOut()).as("timeout scan").isZero();
        assertThat(refundCommands(waiting)).isZero();
        assertThat(refundCommands(cancelled)).isZero();
        assertThat(book(waiting)).isEqualTo(waitingBefore);

        // The recovery scan does: both refunds are due, once each.
        assertThat(sweeper.recoverOutstandingRefunds()).as("refund recovery scan").isEqualTo(2);
        assertRequeuedOnce(waiting, waitingBefore, beforeSweep, 1);
        assertRequeuedOnce(cancelled, cancelledBefore, beforeSweep, 1);

        // Duplicates inside the freshly booked window are throttled — through the sweep ticks...
        assertThat(sweeper.cancelTimedOut()).isZero();
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        // ...and through direct calls with the very cutoff that just made the refund due.
        OffsetDateTime dueBefore = now().minus(refundRetryInterval);
        assertThat(saga.recoverOutstandingRefund(waiting, "Test: duplicate", dueBefore)).isFalse();
        assertThat(saga.recoverOutstandingRefund(cancelled, "Test: duplicate", dueBefore)).isFalse();
        assertThat(refundCommands(waiting)).isEqualTo(1);
        assertThat(refundCommands(cancelled)).isEqualTo(1);

        // One retry interval later (the booked attempt aged by hand) the next window opens: again
        // exactly one more command each, and the timeout scan still stays out of it.
        age(DUE_AGE, waiting, cancelled);
        assertThat(sweeper.cancelTimedOut()).isZero();
        assertThat(sweeper.recoverOutstandingRefunds()).isEqualTo(2);
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        assertThat(refundCommands(waiting)).isEqualTo(2);
        assertThat(refundCommands(cancelled)).isEqualTo(2);
    }

    @Test
    void concurrentScansRequeueADueRefundExactlyOnce() throws Exception {
        UUID waiting = refundOwingOrder(OrderStatus.CANCELLATION_PENDING);
        age(DUE_AGE, waiting);
        Book before = book(waiting);
        OffsetDateTime beforeScans = now();

        // Two recovery scans and one timeout scan fire at the same instant. Whatever the
        // interleaving, one refund window has one winner: the loser of the optimistic-locking race
        // retries on fresh data, finds the booked stamp and stands down — and the timeout scan
        // does not take part at all.
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CyclicBarrier start = new CyclicBarrier(3);
            Callable<Integer> recovery = () -> {
                start.await(10, TimeUnit.SECONDS);
                return sweeper.recoverOutstandingRefunds();
            };
            Callable<Integer> timeoutScan = () -> {
                start.await(10, TimeUnit.SECONDS);
                return sweeper.cancelTimedOut();
            };
            Future<Integer> first = pool.submit(recovery);
            Future<Integer> second = pool.submit(recovery);
            Future<Integer> third = pool.submit(timeoutScan);
            assertThat(first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS))
                    .as("refunds re-queued by the two recovery scans").isEqualTo(1);
            assertThat(third.get(30, TimeUnit.SECONDS)).as("timeout scan").isZero();
        }
        finally {
            pool.shutdownNow();
        }

        assertRequeuedOnce(waiting, before, beforeScans, 1);
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
    }

    @Test
    void aSettledRefundIsNeverRequeuedAgain() {
        UUID waiting = refundOwingOrder(OrderStatus.CANCELLATION_PENDING);
        UUID cancelled = refundOwingOrder(OrderStatus.CANCELLED);
        age(DUE_AGE, waiting, cancelled);
        assertThat(sweeper.recoverOutstandingRefunds()).isEqualTo(2);

        // The refund comes back: the waiting order finishes its cancellation, the cancelled one
        // only closes its flag.
        for (UUID orderId : List.of(waiting, cancelled)) {
            Order order = orders.findById(orderId).orElseThrow();
            saga.paymentRefunded(UUID.randomUUID(), new PaymentRefunded(
                    orderId, order.getPaymentId(), order.getTotalAmount(), order.getCurrency(), "refund"));
            assertThat(orders.findById(orderId).orElseThrow().getRefundStatus())
                    .isEqualTo(RefundStatus.REFUNDED);
            assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }
        Book waitingSettled = book(waiting);
        Book cancelledSettled = book(cancelled);

        // However old the settled rows look, neither scan has anything to say about them.
        age(DUE_AGE, waiting, cancelled);
        assertThat(sweeper.cancelTimedOut()).isZero();
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        assertThat(saga.timeOut(waiting, "Test: late timeout")).isFalse();
        assertThat(saga.recoverOutstandingRefund(
                cancelled, "Test: late recovery", now().plus(refundRetryInterval))).isFalse();

        assertThat(refundCommands(waiting)).isEqualTo(1);
        assertThat(refundCommands(cancelled)).isEqualTo(1);
        assertThat(book(waiting).outbox()).isEqualTo(waitingSettled.outbox());
        assertThat(book(cancelled).outbox()).isEqualTo(cancelledSettled.outbox());
        // The cancellation is announced once, by the refund's completion for the waiting order and
        // not at all again for the order that was announced when it was cancelled.
        assertThat(book(waiting).outbox()).containsOnlyOnce("OrderCancelled");
    }

    // ------------------------------------------------------------------ candidate selection

    @Test
    void refundsThatAreNotDueDoNotCrowdStuckOrdersOutOfTheTimeoutBatch() {
        // 55 refunds older than any ordinary stuck order and past the order timeout: in front of
        // them in a "stuck orders, oldest first" batch of 50, genuinely stuck orders are never
        // reached.
        List<UUID> waiting = refundOwingOrders(55, OrderStatus.CANCELLATION_PENDING);
        age(NOT_DUE_AGE, waiting.toArray(UUID[]::new));
        List<Book> waitingBefore = waiting.stream().map(this::book).toList();

        UUID stuckPending = stuckOrder(false);
        UUID stuckPaying = stuckOrder(true);
        UUID stuckPendingToo = stuckOrder(false);
        age(STUCK_AGE, stuckPending, stuckPaying, stuckPendingToo);
        // Fresh: the saga is still within its timeout for this one.
        UUID fresh = stuckOrder(false);
        Book freshBefore = book(fresh);

        // Ordinary timeout compensation, unchanged: exactly the three stuck orders are cancelled
        // with their stock released and the cancellation announced — no refund involved.
        assertThat(sweeper.cancelTimedOut()).isEqualTo(3);
        for (UUID orderId : List.of(stuckPending, stuckPaying, stuckPendingToo)) {
            Book cancelledOrder = book(orderId);
            assertThat(cancelledOrder.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(cancelledOrder.reason()).contains("did not complete within");
            assertThat(cancelledOrder.outbox()).containsOnlyOnce("ReleaseStock").containsOnlyOnce("OrderCancelled")
                    .doesNotContain(REFUND);
        }
        assertThat(book(fresh)).isEqualTo(freshBefore);

        // The 55 waiting orders were not touched by it: no re-queue (their retry is not due), no
        // state change, no write at all.
        assertThat(waiting.stream().map(this::book).toList()).isEqualTo(waitingBefore);
        assertThat(outbox.findAll()).extracting(OutboxEvent::getAggregateId).doesNotContainAnyElementsOf(waiting);
        // Nor does the recovery scan have anything due.
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        assertThat(sweeper.cancelTimedOut()).isZero();
    }

    @Test
    void dueRefundsDoNotCrowdStuckOrdersOutEitherAndRecoveryStillDrainsThemInBatches() {
        List<UUID> waiting = refundOwingOrders(55, OrderStatus.CANCELLATION_PENDING);
        age(DUE_AGE, waiting.toArray(UUID[]::new));
        UUID stuckPending = stuckOrder(false);
        UUID stuckPaying = stuckOrder(true);
        age(STUCK_AGE, stuckPending, stuckPaying);

        // Due refunds are the recovery scan's work: the timeout scan reaches the stuck orders anyway.
        assertThat(sweeper.cancelTimedOut()).isEqualTo(2);
        assertThat(book(stuckPending).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(book(stuckPaying).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(waiting).allSatisfy(orderId -> assertThat(refundCommands(orderId)).isZero());

        // The recovery scan is capped at 50 per run and drains the backlog without re-queueing
        // anything twice: 50, then the remaining 5, then nothing.
        assertThat(sweeper.recoverOutstandingRefunds()).isEqualTo(50);
        assertThat(sweeper.recoverOutstandingRefunds()).isEqualTo(5);
        assertThat(sweeper.recoverOutstandingRefunds()).isZero();
        assertThat(waiting).allSatisfy(orderId -> {
            assertThat(refundCommands(orderId)).isEqualTo(1);
            assertThat(book(orderId).status()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        });
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * @param status {@code CANCELLATION_PENDING} (a confirmed order whose customer cancelled, waiting
     *               for its money) or {@code CANCELLED} (charged late, after the cancellation)
     * @return a persisted order with a tracked payment, a {@code REQUESTED} refund stamped
     *         just now and no outbox rows at all (the first command is not the test's business)
     */
    private UUID refundOwingOrder(OrderStatus status) {
        return refundOwingOrders(1, status).get(0);
    }

    private List<UUID> refundOwingOrders(int count, OrderStatus status) {
        List<Order> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Order order = pricedOrder();
            UUID payment = UUID.randomUUID();
            if (status == OrderStatus.CANCELLATION_PENDING) {
                order.markConfirmed(payment);
                assertThat(order.requestRefund()).isTrue();
                order.markCancellationPending(OrderSagaTransactions.CANCELLED_BY_CUSTOMER);
            }
            else {
                order.rememberPayment(payment);
                assertThat(order.requestRefund()).isTrue();
                order.markCancelled(OrderSagaTransactions.CANCELLED_BY_CUSTOMER);
            }
            batch.add(order);
        }
        orders.saveAll(batch);
        return batch.stream().map(Order::getId).toList();
    }

    /** @return a fresh order the saga is still waiting on ({@code PENDING}, or {@code PAYMENT_PENDING}) */
    private UUID stuckOrder(boolean paymentPending) {
        Order order = pricedOrder();
        if (paymentPending) {
            order.markPaymentPending(UUID.randomUUID());
        }
        return orders.saveAndFlush(order).getId();
    }

    private static Order pricedOrder() {
        Order order = Order.pending(UUID.randomUUID(), "EUR", null);
        order.addLine(UUID.randomUUID(), "Widget", new BigDecimal("12.50"), 2);
        return order;
    }

    /**
     * Lets time pass without waiting: the rows look as if their last transition — and, for a refund,
     * its last command — were {@code age} ago. Plain SQL, so neither {@code @Version} nor
     * {@code @PreUpdate} moves.
     */
    private void age(Duration age, UUID... orderIds) {
        OffsetDateTime then = now().minus(age);
        for (UUID orderId : orderIds) {
            jdbc.update("UPDATE orders SET updated_at = ?, refund_retry_at = "
                    + "CASE WHEN refund_status = 'NONE' THEN refund_retry_at ELSE ? END WHERE id = ?",
                    then, then, orderId);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ assertions & infra

    /**
     * @param orderId        the order
     * @param before         its book before the re-queue
     * @param notBefore      the re-queue's retry stamp must be at or after this instant
     * @param expectedWrites how many committed writes the order must show since {@code before}
     */
    private void assertRequeuedOnce(UUID orderId, Book before, OffsetDateTime notBefore, int expectedWrites) {
        Book after = book(orderId);
        // Command and stamp are one commit: one command, naming the tracked payment, and a fresh
        // retry stamp...
        assertThat(refundCommands(orderId)).isEqualTo(1);
        OutboxEvent command = outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> REFUND.equals(row.getEventType())).findFirst().orElseThrow();
        assertThat(payloadOf(command).get("payload").get("paymentId").asText())
                .isEqualTo(before.payment().toString());
        assertThat(after.retryAt()).isAfterOrEqualTo(notBefore);
        assertThat(after.version()).isEqualTo(before.version() + expectedWrites);
        // ...and nothing else about the order moved: status, reason, payment identity, refund flag,
        // and no stock release or cancellation announcement was queued by the retry.
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.reason()).isEqualTo(before.reason());
        assertThat(after.payment()).isEqualTo(before.payment());
        assertThat(after.refund()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(after.outbox()).containsExactly(REFUND);
    }

    private long refundCommands(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .filter(row -> REFUND.equals(row.getEventType()))
                .count();
    }

    private Book book(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        return new Book(order.getStatus(), order.getRefundStatus(), order.getVersion(),
                order.getRefundRetryAt(), order.getUpdatedAt(), order.getFailureReason(), order.getPaymentId(),
                outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream().map(OutboxEvent::getEventType).toList());
    }

    private JsonNode payloadOf(OutboxEvent row) {
        try {
            return parse(row.getPayload());
        }
        catch (IOException e) {
            throw new UncheckedIOException("Outbox payload is not JSON", e);
        }
    }

    /** Everything about one order a retry must (not) change, in one comparable value. */
    private record Book(
            OrderStatus status,
            RefundStatus refund,
            long version,
            OffsetDateTime retryAt,
            OffsetDateTime updatedAt,
            String reason,
            UUID payment,
            List<String> outbox) {
    }
}
