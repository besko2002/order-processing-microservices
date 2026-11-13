package com.example.orders.inventory.service;

import com.example.orders.inventory.client.OrderStatusClient;
import com.example.orders.inventory.client.OrderStatusVerdict;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.support.error.BadRequestException;
import com.example.orders.support.tx.OptimisticRetry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Operator tooling for reservations that leaked <em>before</em> the release-intent fix: a canceled
 * order whose late {@code StockReserved} was processed by this service (the orchestrator had
 * already dropped it), leaving {@code RESERVED} rows nobody ever gave back.
 *
 * <p>What makes this safe to run by hand — and what does <em>not</em>:
 * <ul>
 *   <li><b>The state of the order is verified by the server, not asserted by the caller.</b> Before
 *       any release or intent for an order, this service asks order-service
 *       ({@link OrderStatusClient}, {@code GET /api/orders/{id}}) with the administrator's own
 *       bearer token and proceeds only when the answer is a well-formed {@code CANCELLED} or
 *       {@code REJECTED} for exactly that order. Those two states are immutable, so the answer
 *       stays true during the order's own transaction that follows. An active order (including
 *       {@code CONFIRMED}), an unknown one, a denied token, an unreachable or misbehaving
 *       order-service, a malformed or foreign answer: all refused with a per-order outcome and no
 *       effect on stock, reservations, intents or the outbox. A list of ids from a human, a SQL
 *       query in a runbook or the caller's role is <em>not</em> proof of cancellation.</li>
 *   <li>The network call happens outside every inventory transaction and lock; nothing is held
 *       while order-service is slow.</li>
 *   <li>Each verified order is one transaction ({@code REQUIRES_NEW}) serialized with the running
 *       saga by the per-order advisory lock — a leaking hold can be recovered while live traffic
 *       runs.</li>
 *   <li>Only {@code RESERVED} rows move; an order with {@code COMMITTED} rows is refused entirely
 *       even if it is cancelled (second defense), so a wrong id can never turn a legitimate sale
 *       into merchandise — the refund flow's own {@code ReleaseStock} does that. Released rows and
 *       empty orders are no-ops, and every accepted order gets a release intent, which is what
 *       makes recovery idempotent and closes the "old release consumed before the fix" hole for
 *       future {@code OrderCreated} duplicates.</li>
 *   <li>Bounded failure: after {@value #LOOKUP_FAILURE_LIMIT} consecutive dependency failures
 *       (unreachable, timeout, 5xx, denied token, untrustworthy answers) the rest of the batch is
 *       reported as skipped instead of sending up to 200 more calls into the same failure.</li>
 * </ul>
 */
@Service
public class ReservationReconciliation {

    /** Hard ceiling so one request cannot ask to reconcile thousands of orders at once. */
    public static final int MAX_ORDER_IDS = 200;

    /** Consecutive order-service failures after which the remaining ids are skipped, not asked. */
    public static final int LOOKUP_FAILURE_LIMIT = 3;

    /** Default reason stored with the release intents this run creates. */
    public static final String DEFAULT_REASON = "Recovered leaked reservation";

    private static final Logger log = LoggerFactory.getLogger(ReservationReconciliation.class);

    private final StockReservationTransactions transactions;
    private final OrderStatusClient orderStatus;
    private final int maxAttempts;

    ReservationReconciliation(
            StockReservationTransactions transactions,
            OrderStatusClient orderStatus,
            @Value("${inventory.reservation.max-attempts:3}") int maxAttempts) {
        this.transactions = transactions;
        this.orderStatus = orderStatus;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param orderIds    orders to recover; duplicates are collapsed (one lookup, one line), order
     *                    preserved for the report; at most {@value #MAX_ORDER_IDS} entries, none
     *                    {@code null}
     * @param reason      what the operator writes into the intents; blank falls back to the default
     * @param bearerToken the calling administrator's JWT, forwarded to order-service to verify each
     *                    order's state; never logged
     * @return one line per distinct order id, in the order given
     * @throws BadRequestException when the list is missing, empty, too long or holds a
     *                            {@code null}, or when no token is available to verify with
     */
    public List<ReconciliationLine> recover(List<UUID> orderIds, String reason, String bearerToken) {
        Set<UUID> distinct = validated(orderIds);
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new BadRequestException("An administrator token is required to verify the orders' state");
        }
        String effectiveReason = reason == null || reason.isBlank()
                ? DEFAULT_REASON
                : reason.trim();
        List<ReconciliationLine> lines = new ArrayList<>(distinct.size());
        int consecutiveFailures = 0;
        for (UUID orderId : distinct) {
            if (consecutiveFailures >= LOOKUP_FAILURE_LIMIT) {
                lines.add(new ReconciliationLine(orderId, ReconciliationOutcome.SKIPPED_VERIFICATION_UNAVAILABLE));
                continue;
            }
            // Network I/O first, outside any transaction or lock.
            OrderStatusVerdict verdict = orderStatus.check(orderId, bearerToken);
            consecutiveFailures = verdict.isDependencyFailure() ? consecutiveFailures + 1 : 0;
            ReconciliationOutcome outcome = verdict.permitsRelease()
                    ? OptimisticRetry.execute(
                            maxAttempts, () -> transactions.reconcileRelease(orderId, effectiveReason))
                    : refusal(verdict);
            log.info("Recovery: order {} (order-service says {}) -> {}", orderId, verdict, outcome);
            lines.add(new ReconciliationLine(orderId, outcome));
        }
        return lines;
    }

    /** @param orderId an order to inspect for leaked holds */
    public List<StockReservation> reservationsOf(UUID orderId) {
        return transactions.findByOrder(orderId);
    }

    private static Set<UUID> validated(List<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new BadRequestException("At least one order id is required");
        }
        // A LinkedHashSet keeps "order preserved for the report" — an API promise the response
        // carries — where a HashSet would hand the operator the lines in hash order.
        Set<UUID> distinct = new LinkedHashSet<>();
        for (UUID orderId : orderIds) {
            if (orderId == null) {
                throw new BadRequestException("Order ids must not contain null");
            }
            distinct.add(orderId);
        }
        if (orderIds.size() > MAX_ORDER_IDS) {
            throw new BadRequestException("At most " + MAX_ORDER_IDS + " order ids per request");
        }
        return distinct;
    }

    private static ReconciliationOutcome refusal(OrderStatusVerdict verdict) {
        return switch (verdict) {
            case ACTIVE -> ReconciliationOutcome.REFUSED_ACTIVE_ORDER;
            case NOT_FOUND -> ReconciliationOutcome.REFUSED_ORDER_NOT_FOUND;
            case ACCESS_DENIED -> ReconciliationOutcome.REFUSED_ACCESS_DENIED;
            case UNVERIFIABLE -> ReconciliationOutcome.REFUSED_UNVERIFIABLE;
            case UNAVAILABLE -> ReconciliationOutcome.REFUSED_VERIFICATION_UNAVAILABLE;
            case CANCELLED, REJECTED -> throw new IllegalStateException("releasable verdict has no refusal");
        };
    }

    /**
     * @param orderId order the line is about
     * @param outcome what was done
     */
    public record ReconciliationLine(UUID orderId, ReconciliationOutcome outcome) {
    }
}
