package com.example.orders.order.saga;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The saga's deadline — and the refund's retry clock.
 *
 * <p>An orchestrated saga has one failure mode no amount of retrying fixes: a step that never
 * answers. inventory-service may be down while the order is {@code PENDING}, a
 * {@code ProcessPayment} may have been dead-lettered, a service may have been redeployed mid-flow.
 * Without a deadline the order would sit in a non-final state forever — and worse, keep its stock
 * reserved — so after {@code ORDER_SAGA_TIMEOUT} the sweeper cancels it <em>with</em> compensation.
 *
 * <p>The timeout is measured from the last transition, not from creation: an order that is making
 * progress is not stuck. It is generous compared to the saga's normal latency (which is
 * milliseconds), because cancelling an order that was merely slow is itself a failure.
 *
 * <p>One kind of "stuck" is not fixable by cancelling: a tracked refund whose
 * {@code RefundPayment} command was lost. Money has been (or is being) taken, the order may already
 * be final, and the only cure is to ask for the refund again. A second scan re-commands refunds
 * that are unanswered after {@code orders.saga.refund-retry-interval}, oldest attempt first. Both
 * scans share the sweep tick; the per-order work stays in per-order transactions, so neither scan
 * holds locks across orders and a failure on one order does not roll back the others. Delivery of
 * the re-queued command remains Kafka's at-least-once — the exactly-once effects at the edges are
 * the consumers' flags (payment-service refunds exactly once per order), not the sweep's claim.
 *
 * <p><b>The refund scan is the only driver of refund retries.</b> The two clocks are independent
 * ({@code orders.saga.timeout} against {@code orders.saga.refund-retry-interval}, in either order),
 * so the timeout scan leaves every order with an outstanding refund alone — in its query, not in
 * its loop, so such orders cannot fill the capped batch either — and a refund is re-commanded only
 * once {@code refund_retry_at} is a full retry interval old.
 */
@Component
@ConditionalOnProperty(name = "orders.saga.timeout-sweeper-enabled", havingValue = "true", matchIfMissing = true)
class OrderSagaTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaTimeoutSweeper.class);

    private final OrderRepository orders;
    private final OrderSaga saga;
    private final Duration timeout;
    private final Duration refundRetryInterval;

    OrderSagaTimeoutSweeper(
            OrderRepository orders,
            OrderSaga saga,
            @Value("${orders.saga.timeout:PT2M}") Duration timeout,
            @Value("${orders.saga.refund-retry-interval:PT30S}") Duration refundRetryInterval) {
        this.orders = orders;
        this.saga = saga;
        this.timeout = timeout;
        this.refundRetryInterval = refundRetryInterval;
    }

    @Scheduled(
            fixedDelayString = "${orders.saga.sweep-interval-ms:15000}",
            initialDelayString = "${orders.saga.sweep-interval-ms:15000}")
    void sweep() {
        int cancelled = cancelTimedOut();
        int recovered = recoverOutstandingRefunds();
        if (cancelled + recovered > 0) {
            log.info("Sweep finished: {} order(s) cancelled, {} refund(s) re-queued",
                    cancelled, recovered);
        }
    }

    /**
     * Cancels every order that has been stuck for longer than the timeout.
     *
     * <p>Orders that are only waiting for an outstanding refund ({@code REQUESTED}) are not stuck
     * and are not candidates: re-commanding their refund is {@link #recoverOutstandingRefunds()}'s
     * job, paced by the retry interval, never by this timeout.
     *
     * <p>The scan is read-only and each cancellation is its own transaction, so a sweep never holds
     * locks across orders and a failure on one order does not roll back the others.
     *
     * @return how many orders this run cancelled
     */
    @Transactional(readOnly = true)
    public int cancelTimedOut() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(timeout);
        List<Order> stuck = orders.findTop50ByStatusInAndRefundStatusNotAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                OrderStatus.UNFINISHED, RefundStatus.REQUESTED, cutoff);
        if (stuck.isEmpty()) {
            return 0;
        }
        log.warn("Saga timeout sweep found {} order(s) with no progress since {}", stuck.size(), cutoff);
        int cancelled = 0;
        for (Order order : stuck) {
            String reason = "The order was cancelled because step '%s' did not complete within %s"
                    .formatted(order.getStatus(), timeout);
            if (saga.timeOut(order.getId(), reason)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * Re-commands every outstanding refund that has now been unanswered for a full retry interval,
     * whatever happened to its order — a cancellation that is still waiting, or a cancellation that
     * already went final {@code CANCELLED} with a late payment changing its mind. Only the refund
     * is retried; neither the state nor its announcement is touched by this scan.
     *
     * <p>The scan is read-only and each recovery is its own transaction; booking the attempt and
     * queuing the command commit together inside it, so no attempt goes unbooked.
     *
     * @return how many refunds this run re-queued
     */
    @Transactional(readOnly = true)
    public int recoverOutstandingRefunds() {
        OffsetDateTime dueBefore = OffsetDateTime.now(ZoneOffset.UTC).minus(refundRetryInterval);
        List<Order> owed = orders.findTop50ByRefundStatusAndStatusInAndRefundRetryAtBeforeOrderByRefundRetryAtAsc(
                RefundStatus.REQUESTED,
                Set.of(OrderStatus.CANCELLED, OrderStatus.CANCELLATION_PENDING),
                dueBefore);
        if (owed.isEmpty()) {
            return 0;
        }
        log.warn("Refund recovery sweep found {} refund(s) unanswered since before {}",
                owed.size(), dueBefore);
        int recovered = 0;
        for (Order order : owed) {
            String reason = ("The refund was commanded again because the payment-service did not"
                    + " confirm the first command within %s").formatted(refundRetryInterval);
            if (saga.recoverOutstandingRefund(order.getId(), reason, dueBefore)) {
                recovered++;
            }
        }
        return recovered;
    }
}
