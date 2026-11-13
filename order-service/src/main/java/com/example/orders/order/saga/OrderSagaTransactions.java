package com.example.orders.order.saga;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.OrderCancelled;
import com.example.orders.common.events.OrderConfirmed;
import com.example.orders.common.events.OrderRejected;
import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.PaymentFailed;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.ProcessPayment;
import com.example.orders.common.events.RefundPayment;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.common.events.StockRejected;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.order.api.OrderResponse;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import com.example.orders.support.error.ConflictException;
import com.example.orders.support.error.NotFoundException;
import com.example.orders.support.idempotency.ProcessedEvents;
import com.example.orders.support.outbox.OutboxWriter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write the saga performs, one transaction per method.
 *
 * <p>Each method does the same three things in the same order, and that order is the whole
 * correctness argument:
 * <ol>
 *   <li><b>Claim the event.</b> {@code processedEvents.markProcessed} inserts the de-duplication
 *       note inside this transaction. A redelivery finds the note and returns without touching
 *       anything; a rollback takes the note with it, so the redelivery does the work for real.</li>
 *   <li><b>Guard the transition.</b> An event for an order in an unexpected state is logged at WARN
 *       and dropped. Kafka keeps per-order ordering (the key is the order id), but a retry, a
 *       replayed outbox row or a customer cancelling at the same moment can still produce a
 *       late event — and a late event must never overwrite a final state. A late event for which
 *       the saga still owes work (a payment that completed for a cancelled order, a reservation that
 *       outlived it) is compensated on the spot instead of dropped — see
 *       {@link #compensateLatePayment} and {@link #onStockReserved}.</li>
 *   <li><b>Change state and queue the next messages together.</b> The status update and the outbox
 *       rows commit atomically, so the saga can never be in a state it has not announced, nor
 *       announce a state it is not in.</li>
 * </ol>
 *
 * <p><b>Refund bookkeeping</b> rides on {@code orders.refund_status}, not on the status enum:
 * a cancellation is a <em>final</em> state, yet a charge that completes while the order is already
 * cancelled still owes the customer their money. Every queued {@code RefundPayment} command and the
 * {@code REQUESTED}/{@code REFUNDED} flag flip happen in one transaction, so "refund requested" is
 * never a lie, a lost command cannot strand a tracked refund silently, and a duplicate late payment
 * (different event id, same payment) finds {@code REQUESTED} and stops instead of queueing a second
 * command. Two saga events that race on the same order are settled by the row's optimistic-locking
 * version: the loser retries ({@link OrderSaga}) and re-reads the new {@code refund_status}, which
 * is exactly how concurrent cancellation and payment completion converge on one refund. Refunds
 * whose answer never comes are re-commanded on a deliberate cadence: {@code orders.refund_retry_at}
 * (booked together with each command) grounds the recovery sweep's retry interval — see
 * {@link OrderSagaTimeoutSweeper} and {@code V6__refund_recovery.sql}.
 *
 * <p>A separate bean from {@link OrderSaga} on purpose: the retry around an optimistic-locking
 * conflict has to start a <em>new</em> transaction, which a self-invocation would not do.
 */
@Component
public class OrderSagaTransactions {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaTransactions.class);

    /** Consumer name recorded in {@code processed_events} for the inventory topic. */
    public static final String INVENTORY_CONSUMER = "order.inventory-events";

    /** Consumer name recorded in {@code processed_events} for the payment topic. */
    public static final String PAYMENT_CONSUMER = "order.payment-events";

    /** Reason stored when the customer cancels. */
    public static final String CANCELLED_BY_CUSTOMER = "Cancelled by the customer";

    private final OrderRepository orders;
    private final OutboxWriter outbox;
    private final ProcessedEvents processedEvents;

    OrderSagaTransactions(OrderRepository orders, OutboxWriter outbox, ProcessedEvents processedEvents) {
        this.orders = orders;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
    }

    // ------------------------------------------------------------------ inventory answers

    /**
     * Stock is held: remember the reservation and ask payment-service for the money.
     *
     * <p>Defensively, a {@code StockReserved} that arrives when the saga has already given up on the
     * order does not stay unremembered: the reservation is recorded and a {@code ReleaseStock} is
     * queued (again) in the same transaction. That covers the corner where a cancellation's release
     * was consumed before the reservation existed — the release cannot release what is not there
     * yet, and re-asserting it here is what turns "released before reserved" into "eventually
     * released" without a leak. See {@code InventorySagaTransactions} for the other half: inventory
     * records the release intent so a later {@code OrderCreated} cannot reserve in the first place.
     *
     * @param eventId id of the {@code StockReserved} event
     * @param event   its payload
     */
    @Transactional
    public void onStockReserved(UUID eventId, StockReserved event) {
        if (!processedEvents.markProcessed(eventId, INVENTORY_CONSUMER)) {
            return;
        }
        Order order = find(event.orderId(), "StockReserved");
        if (order == null) {
            return;
        }
        if (order.getStatus() == OrderStatus.PENDING) {
            order.markPaymentPending(event.reservationId());
            orders.saveAndFlush(order);
            outbox.record(order.getId(), ProcessPayment.class.getSimpleName(), Topics.PAYMENT_COMMANDS,
                    new ProcessPayment(
                            order.getId(), order.getUserId(), order.getTotalAmount(), order.getCurrency()));
            log.info("Order {} has stock reserved ({}), asking for payment of {} {}",
                    order.getId(), event.reservationId(), order.getTotalAmount(), order.getCurrency());
            return;
        }
        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.CANCELLATION_PENDING
                || order.getStatus() == OrderStatus.REJECTED) {
            // The saga no longer wants this stock. Remember it (compensation lists it) and make
            // sure a release is actually out; inventory-service applies releases idempotently, and
            // ordering inside its commands topic makes the second release a no-op. Without this,
            // stock reserved after the cancellation would sit in reserved_quantity forever.
            //
            // REJECTED belongs here too: a rejection never queues a release of its own (nothing was
            // held when inventory said no), so a StockReserved that still reaches a rejected order —
            // a re-processed OrderCreated after a restock, past the de-duplication window — is a
            // hold nobody else will ever give back. The status stays REJECTED; only the release is
            // queued.
            if (order.getReservationId() == null) {
                order.rememberReservation(event.reservationId());
                orders.saveAndFlush(order);
                releaseStock(order, cancellationReason(order));
                log.warn("Late StockReserved for {} order {}: reservation {} re-released",
                        order.getStatus(), order.getId(), event.reservationId());
            }
            else {
                // The release went out when the cancellation happened; the reservation was merely
                // an event we already knew about.
                ignored("StockReserved", order);
            }
            return;
        }
        // STOCK_RESERVED / PAYMENT_PENDING replayed answers, or a final CONFIRMED event that must
        // never overwrite its state: keep remembering reservations of unfinished orders, drop
        // everything else.
        if (order.getReservationId() == null && !order.getStatus().isFinal()) {
            order.rememberReservation(event.reservationId());
            orders.saveAndFlush(order);
        }
        ignored("StockReserved", order);
    }

    /**
     * Stock said no: the order is rejected, and nothing has to be undone.
     *
     * @param eventId id of the {@code StockRejected} event
     * @param event   its payload
     */
    @Transactional
    public void onStockRejected(UUID eventId, StockRejected event) {
        if (!processedEvents.markProcessed(eventId, INVENTORY_CONSUMER)) {
            return;
        }
        Order order = find(event.orderId(), "StockRejected");
        if (order == null) {
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            ignored("StockRejected", order);
            return;
        }
        String reason = event.reason() == null ? "Stock could not be reserved" : event.reason();
        order.markRejected(reason);
        orders.saveAndFlush(order);
        outbox.record(order.getId(), OrderRejected.class.getSimpleName(), Topics.ORDER_EVENTS,
                new OrderRejected(order.getId(), order.getOrderNumber(), order.getUserId(), reason));
        log.info("Order {} rejected: {}", order.getId(), reason);
    }

    // ------------------------------------------------------------------ payment answers

    /**
     * The money is in: confirm the order and turn the reservation into a sale.
     *
     * <p>Unless the saga has already moved past this step — then the charge is real and the order
     * is cancelled, and the only correct answer is to refund it, tracked to completion
     * ({@link #compensateLatePayment}).
     *
     * @param eventId id of the {@code PaymentCompleted} event
     * @param event   its payload
     */
    @Transactional
    public void onPaymentCompleted(UUID eventId, PaymentCompleted event) {
        if (!processedEvents.markProcessed(eventId, PAYMENT_CONSUMER)) {
            return;
        }
        Order order = find(event.orderId(), "PaymentCompleted");
        if (order == null) {
            return;
        }
        if (OrderStatus.STOCK_HELD.contains(order.getStatus())) {
            order.markConfirmed(event.paymentId());
            orders.saveAndFlush(order);
            outbox.record(order.getId(), OrderConfirmed.class.getSimpleName(), Topics.ORDER_EVENTS,
                    new OrderConfirmed(order.getId(), order.getOrderNumber(), order.getUserId(),
                            order.getTotalAmount(), order.getCurrency()));
            outbox.record(order.getId(), CommitStock.class.getSimpleName(), Topics.INVENTORY_COMMANDS,
                    new CommitStock(order.getId()));
            log.info("Order {} confirmed, payment {}", order.getId(), event.paymentId());
            return;
        }
        compensateLatePayment(order, event);
    }

    /**
     * The charge was declined: compensate the reservation and cancel the order.
     *
     * @param eventId id of the {@code PaymentFailed} event
     * @param event   its payload
     */
    @Transactional
    public void onPaymentFailed(UUID eventId, PaymentFailed event) {
        if (!processedEvents.markProcessed(eventId, PAYMENT_CONSUMER)) {
            return;
        }
        Order order = find(event.orderId(), "PaymentFailed");
        if (order == null) {
            return;
        }
        if (!OrderStatus.STOCK_HELD.contains(order.getStatus())) {
            // A decline for an order no longer waiting for one: rejected (nothing reserved, nothing
            // charged) or already cancelled by the payment's own flow. Nothing is owed either way.
            ignored("PaymentFailed", order);
            return;
        }
        String reason = event.reason() == null ? "Payment failed" : event.reason();
        // Compensation first in the reading order, one transaction in reality: the release command
        // and the new status are the same commit, so the stock cannot stay held for a cancelled
        // order and the order cannot be cancelled without the stock going back.
        releaseStock(order, reason);
        order.markCancelled(reason);
        orders.saveAndFlush(order);
        publishCancelled(order, reason);
        log.info("Order {} cancelled after a failed payment: {}", order.getId(), reason);
    }

    /**
     * The refund came back: a cancellation that was waiting for it is now finished.
     *
     * <p>For an order that was <em>already</em> cancelled when the refund was requested (a late
     * payment, or a re-asserted refund for a lost command), the status is final — only the
     * {@code refund_status} flag is closed here, so a redelivered or re-announced
     * {@code PaymentRefunded} finds {@code REFUNDED} and stops. The cancelled status and its
     * {@code OrderCancelled} event are never repeated.
     *
     * @param eventId id of the {@code PaymentRefunded} event
     * @param event   its payload
     */
    @Transactional
    public void onPaymentRefunded(UUID eventId, PaymentRefunded event) {
        if (!processedEvents.markProcessed(eventId, PAYMENT_CONSUMER)) {
            return;
        }
        Order order = find(event.orderId(), "PaymentRefunded");
        if (order == null) {
            return;
        }
        if (order.getRefundStatus() == RefundStatus.REFUNDED) {
            ignored("PaymentRefunded (refund already settled)", order);
            return;
        }
        if (guardTrackedPayment(order, event.paymentId(), "PaymentRefunded")) {
            // The event names another order's payment. Its refund answer must not touch this
            // order's book in any way: no flag flip, no cancellation, no announcement, and the
            // tracked payment id stays as it is.
            return;
        }
        switch (order.getStatus()) {
            case CANCELLATION_PENDING -> {
                String reason = order.getFailureReason() == null
                        ? CANCELLED_BY_CUSTOMER
                        : order.getFailureReason();
                order.markRefunded();
                order.markCancelled(reason);
                orders.saveAndFlush(order);
                publishCancelled(order, reason);
                log.info("Order {} cancelled, refund {} completed", order.getId(), event.paymentId());
            }
            case CANCELLED -> {
                if (order.getRefundStatus() == RefundStatus.REQUESTED) {
                    // The cancellation was already announced (late payment, sweep retry); only the
                    // refund-tracking flag remains open, and the re-announced completion closing it
                    // must not re-publish OrderCancelled or flip an untracked refund.
                    order.markRefunded();
                    orders.saveAndFlush(order);
                    log.info("Refund {} for already-cancelled order {} completed",
                            event.paymentId(), order.getId());
                }
                else {
                    // A refund this saga never requested (e.g. an order cancelled by a *failed*
                    // payment): the delimiter must stay NIL — this is where a duplicate or foreign
                    // event id may not close a book it never opened.
                    ignored("PaymentRefunded (no tracked refund)", order);
                }
            }
            default -> {
                // CONFIRMED / PENDING / REJECTED never had a tracked refund out; payment-service
                // re-announces refunds and a mismatching event id must not close this order's book.
                ignored("PaymentRefunded", order);
            }
        }
    }

    // ------------------------------------------------------------------ customer cancellation

    /**
     * Cancels an order on the customer's (or an administrator's) request.
     *
     * @param orderId  order to cancel
     * @param callerId the authenticated caller
     * @param admin    whether the caller holds {@code ADMIN}
     * @return the order after the cancellation was started
     * @throws NotFoundException when the order does not exist or belongs to somebody else
     * @throws ConflictException when it is already finished or already being cancelled
     */
    @Transactional
    public OrderResponse cancelOnRequest(UUID orderId, UUID callerId, boolean admin) {
        Order order = orders.findWithItemsById(orderId)
                .filter(candidate -> admin || candidate.getUserId().equals(callerId))
                .orElseThrow(() -> NotFoundException.of("Order", orderId));

        switch (order.getStatus()) {
            case PENDING, STOCK_RESERVED, PAYMENT_PENDING -> {
                // Release unconditionally: the reservation may already exist without this service
                // having heard about it yet, and releasing an unknown order is a no-op in
                // inventory-service (which then also remembers the intent, so a reservation raced
                // in behind the release cannot survive either way). The other order of consumption
                // — reserve first, release after — releases the held rows; both directions are
                // leak-free. The payment: no answer has been recorded yet, so there is nothing to
                // refund here; if the charge completes despite this, the late-payment compensation
                // below refunds it on arrival.
                releaseStock(order, CANCELLED_BY_CUSTOMER);
                refundPayment(order, CANCELLED_BY_CUSTOMER);
                order.markCancelled(CANCELLED_BY_CUSTOMER);
                orders.saveAndFlush(order);
                publishCancelled(order, CANCELLED_BY_CUSTOMER);
                log.info("Order {} cancelled on request while in flight", order.getId());
            }
            case CONFIRMED -> {
                refundPayment(order, CANCELLED_BY_CUSTOMER);
                releaseStock(order, CANCELLED_BY_CUSTOMER);
                if (order.getRefundStatus() == RefundStatus.REQUESTED) {
                    order.markCancellationPending(CANCELLED_BY_CUSTOMER);
                    orders.saveAndFlush(order);
                    log.info("Order {} is being cancelled, waiting for the refund of payment {}",
                            order.getId(), order.getPaymentId());
                }
                else {
                    // Nothing to wait for: no payment was ever recorded on this order.
                    order.markCancelled(CANCELLED_BY_CUSTOMER);
                    orders.saveAndFlush(order);
                    publishCancelled(order, CANCELLED_BY_CUSTOMER);
                }
            }
            case CANCELLATION_PENDING -> throw new ConflictException(
                    "Order is already being cancelled, waiting for the refund to complete");
            case CANCELLED -> throw new ConflictException("Order is already cancelled");
            case REJECTED -> throw new ConflictException("Order was rejected and cannot be cancelled");
            default -> throw new ConflictException("Order cannot be cancelled in state " + order.getStatus());
        }
        return OrderResponse.of(orders.findWithItemsById(orderId).orElseThrow());
    }

    // ------------------------------------------------------------------ saga timeout

    /**
     * Gives up on one stuck order: compensate whatever may be outstanding and cancel it.
     *
     * <p>An order whose refund is already outstanding is <em>not</em> stuck — it is being undone
     * (in-flight) or already undone (final) and is only waiting for the money to come back. Such an
     * order is left exactly as it is: it is not cancelled (the cancellation wording of
     * {@link OrderStatus#CANCELLATION_PENDING} exists for exactly this waiting state), the
     * {@code OrderCancelled} announcement is not repeated, the refund flag stays {@code REQUESTED}
     * — and the refund is <em>not</em> re-commanded either. Re-commanding is a decision about the
     * refund's own clock ({@code refund_retry_at} against the retry interval), and that decision
     * has exactly one owner, {@link #recoverOutstandingRefund}; a retry here would answer to the
     * order timeout instead, which is unrelated to the retry interval and fires early whenever the
     * interval is the longer one.
     *
     * @param orderId order to time out
     * @param reason  what the customer is told
     * @return whether this call changed anything ({@code false} for an order that is gone, final,
     *         or only waiting for its refund)
     */
    @Transactional
    public boolean timeOut(UUID orderId, String reason) {
        Optional<Order> found = orders.findById(orderId);
        if (found.isEmpty()) {
            return false;
        }
        Order order = found.get();
        if (order.getStatus().isFinal()) {
            // It finished between the sweep query and this transaction. A CHARGES-STILL-STANDING
            // order cannot be helped here — an outstanding refund is recovered by the recovery
            // sweep through {@link #recoverOutstandingRefund}, not by re-cancelling the order.
            return false;
        }
        if (order.getRefundStatus() == RefundStatus.REQUESTED) {
            // Not stuck: the money is on its way back, or its command was lost. The sweep's query
            // does not select these rows; this guard covers a direct call and an order that
            // started waiting for its refund between the query and this transaction. Neither the
            // state nor the outbox is touched — the recovery sweep re-commands the refund when,
            // and only when, its retry interval has passed (the stock release went out with the
            // cancellation that requested the refund, so the compensation is covered).
            log.debug("Order {} is waiting for the refund of payment {}: not a timeout, left to refund recovery",
                    order.getId(), order.getPaymentId());
            return false;
        }
        releaseStock(order, reason);
        refundPayment(order, reason);
        order.markCancelled(reason);
        orders.saveAndFlush(order);
        publishCancelled(order, reason);
        log.warn("Order {} timed out in the saga and was cancelled: {}", order.getId(), reason);
        return true;
    }

    /**
     * Re-commands one outstanding refund whose order is cancelled or whose cancellation is only
     * waiting for its money. Deliberately narrow:
     *
     * <ul>
     *   <li>only orders where the payout is the open item are eligible ({@code CANCELLED} or
     *       {@code CANCELLATION_PENDING}, refund {@code REQUESTED}, a payment remembered — the
     *       unfinished states' stuck orders stay the timeout sweep's business);</li>
     *   <li>only refunds that have been unanswered for at least one retry interval, measured from
     *       the last command — the caller passes the sweep's cutoff, and this method re-checks it
     *       inside the transaction so a racing sweep or a direct call cannot re-queue an attempt
     *       that was booked a moment ago;</li>
     *   <li>refunds already settled ({@code REFUNDED}) are never retried — the completion has
     *       been applied, re-commanding would only add noise.</li>
     * </ul>
     *
     * <p>The re-queued command and the new retry timestamp commit atomically. The order is never
     * re-confirmed, its status and cancellation reason are never touched, and no
     * {@code OrderCancelled} is published — the announcement already went out when the
     * cancellation happened (or will, when the refund completes in
     * {@link #onPaymentRefunded}).
     *
     * @param orderId   order to recover
     * @param reason    what the re-commanded payload shows
     * @param dueBefore refunds last commanded at or before this instant are due
     * @return whether this call re-queued a refund
     */
    @Transactional
    public boolean recoverOutstandingRefund(UUID orderId, String reason, OffsetDateTime dueBefore) {
        if (orderId == null) {
            return false;
        }
        Order order = orders.findById(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        if (order.getRefundStatus() != RefundStatus.REQUESTED) {
            // Nothing owed under this flag; a settled refund must not be re-commanded.
            return false;
        }
        if (order.getStatus() != OrderStatus.CANCELLED
                && order.getStatus() != OrderStatus.CANCELLATION_PENDING) {
            // Unfinished states with an outstanding refund are the timeout sweep's business, and
            // this recovery entry point has no business flipping their state.
            return false;
        }
        if (order.getPaymentId() == null) {
            // Defensively: a refund without a tracked payment cannot name one.
            return false;
        }
        OffsetDateTime lastAttempt = order.getRefundRetryAt();
        if (lastAttempt != null && lastAttempt.isAfter(dueBefore)) {
            // A sweep that just ran booked an attempt; re-queueing again immediately would be
            // noise, so wait out the rest of the interval.
            return false;
        }
        retryRefund(order, reason);
        order.bookRefundRetry(OffsetDateTime.now(ZoneOffset.UTC));
        orders.saveAndFlush(order);
        log.warn("Refund of payment {} for {} order {} re-queued (last attempt {})",
                order.getPaymentId(), order.getStatus(), order.getId(), lastAttempt);
        return true;
    }

    // ------------------------------------------------------------------ internals

    /**
     * A payment completed for an order the saga is no longer holding stock for: the cancellation or
     * the timeout beat the answer. The money is authorized in payment-service either way, so the
     * order must not simply be left cancelled with a live charge — it gets refunded here, and
     * {@code refund_status} carries that refund to completion.
     *
     * <p>Why this cannot double-refund: the {@code REQUESTED}/{@code REFUNDED} flags are part of
     * this same transaction; a duplicate late answer with a different event id re-reads the flags
     * (after an optimistic-locking retry, if it raced the first one) and finds the refund already
     * requested. Payment-service itself is idempotent per order, so even a second command could
     * never money twice — but the saga does not command what has already been commanded.
     */
    private void compensateLatePayment(Order order, PaymentCompleted event) {
        if (order.getStatus() != OrderStatus.CANCELLED
                && order.getStatus() != OrderStatus.CANCELLATION_PENDING) {
            // CONFIRMED: was paid normally, a second announced charge cannot be a different one
            // (payment ids are UNIQUE per order); REJECTED: charges are only ever requested while
            // stock is held, so a payment for a rejected order signals a bug elsewhere. Neither
            // leaves the saga owing a refund it has not already handled, and dropping the event
            // must not confirm the former or undo the latter.
            ignored("PaymentCompleted", order);
            return;
        }
        if (guardTrackedPayment(order, event.paymentId(), "PaymentCompleted")) {
            return;
        }
        switch (order.getRefundStatus()) {
            case REFUNDED -> {
                // The refund of this very payment was completed by an earlier delivery.
                ignored("PaymentCompleted (refund already settled)", order);
            }
            case REQUESTED -> {
                // Refund already commanded — the first delivery of this answer won, or the
                // cancellation itself requested it ("payment confirmed before cancellation").
                // A second command would be a duplicate, not a second refund.
                log.info("Late PaymentCompleted for order {}: refund of payment {} is already requested",
                        order.getId(), event.paymentId());
            }
            case NONE -> {
                // First news of the money: track the payment and queue its refund in one commit.
                order.rememberPayment(event.paymentId());
                order.requestRefund();
                orders.saveAndFlush(order);
                outbox.record(order.getId(), RefundPayment.class.getSimpleName(), Topics.PAYMENT_COMMANDS,
                        new RefundPayment(order.getId(), event.paymentId(), order.getTotalAmount(),
                                order.getCurrency(), "The order was already cancelled when its payment completed"));
                log.warn("Late PaymentCompleted for {} order {}: refund of payment {} requested",
                        order.getStatus() == OrderStatus.CANCELLATION_PENDING
                                ? "a cancelling" : "a cancelled", order.getId(), event.paymentId());
            }
        }
    }

    /**
     * @param order     order the event arrived for
     * @param paymentId payment named by the event
     * @param eventName for the log line
     * @return {@code true} when the event id does not belong to the payment this order tracks and
     *         the caller must stop. Payments are keyed {@code UNIQUE(order_id)} in payment-service;
     *         a different id means a redelivered outbox row from a foreign environment or a bug.
     */
    private boolean guardTrackedPayment(Order order, UUID paymentId, String eventName) {
        if (order.getPaymentId() != null && paymentId != null
                && !order.getPaymentId().equals(paymentId)) {
            log.warn("Ignoring {} naming payment {} for order {} which tracks payment {}",
                    eventName, paymentId, order.getId(), order.getPaymentId());
            return true;
        }
        return false;
    }

    private Order find(UUID orderId, String eventName) {
        if (orderId == null) {
            log.warn("Ignoring {} without an order id", eventName);
            return null;
        }
        Order order = orders.findById(orderId).orElse(null);
        if (order == null) {
            // Not a data error we can fix: this service owns the orders, so an unknown id means the
            // message belongs to another environment or to an order that was purged.
            log.warn("Ignoring {} for unknown order {}", eventName, orderId);
        }
        return order;
    }

    private static void ignored(String eventName, Order order) {
        log.warn("Ignoring {} for order {}: unexpected in state {} (refund {})",
                eventName, order.getId(), order.getStatus(), order.getRefundStatus());
    }

    private void releaseStock(Order order, String reason) {
        outbox.record(order.getId(), ReleaseStock.class.getSimpleName(), Topics.INVENTORY_COMMANDS,
                // The lines are left empty on purpose: inventory-service owns the reservation rows
                // and finds them by order id. Repeating our copy of the lines here would invite the
                // two services to disagree about what was reserved.
                new ReleaseStock(order.getId(), order.getReservationId(), reason, List.of()));
    }

    /**
     * Requests the refund of the order's recorded payment, exactly once. Callers queue the command
     * and flip {@code refund_status} in the same transaction via
     * {@link Order#requestRefund()} — the flag is never ahead of the command.
     *
     * @param order  order whose payment is being refunded
     * @param reason shown to the customer/document
     */
    private void refundPayment(Order order, String reason) {
        if (order.getPaymentId() == null) {
            // Never charged. That is normal: cancellation can overtake the payment, and a charge
            // that still completes is compensated by its own answer ({@link #compensateLatePayment}).
            return;
        }
        if (!order.requestRefund()) {
            // Already requested (the answer to a refund may simply be in flight). No second command.
            log.info("Refund of payment {} for order {} is already requested, not queueing it again",
                    order.getPaymentId(), order.getId());
            return;
        }
        outbox.record(order.getId(), RefundPayment.class.getSimpleName(), Topics.PAYMENT_COMMANDS,
                new RefundPayment(order.getId(), order.getPaymentId(), order.getTotalAmount(),
                        order.getCurrency(), reason));
    }

    /**
     * Re-commands a refund whose first attempt never completed. Reserved for the one deliberate
     * retry path, {@link #recoverOutstandingRefund}, which checks the retry interval first — so an
     * outstanding refund is never stranded by a lost {@code RefundPayment} message, and never
     * re-commanded ahead of its time. The caller books the attempt in the same transaction.
     */
    private void retryRefund(Order order, String reason) {
        outbox.record(order.getId(), RefundPayment.class.getSimpleName(), Topics.PAYMENT_COMMANDS,
                new RefundPayment(order.getId(), order.getPaymentId(), order.getTotalAmount(),
                        order.getCurrency(), reason));
    }

    /** @param order an order being compensated with {@code releaseStock} */
    private static String cancellationReason(Order order) {
        return order.getFailureReason() == null ? CANCELLED_BY_CUSTOMER : order.getFailureReason();
    }

    private void publishCancelled(Order order, String reason) {
        outbox.record(order.getId(), OrderCancelled.class.getSimpleName(), Topics.ORDER_EVENTS,
                new OrderCancelled(order.getId(), order.getOrderNumber(), order.getUserId(), reason));
    }
}
