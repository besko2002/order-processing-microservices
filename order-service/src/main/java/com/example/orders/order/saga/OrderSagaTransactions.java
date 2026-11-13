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
import com.example.orders.support.error.ConflictException;
import com.example.orders.support.error.NotFoundException;
import com.example.orders.support.idempotency.ProcessedEvents;
import com.example.orders.support.outbox.OutboxWriter;
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
 *       late event — and a late event must never overwrite a final state.</li>
 *   <li><b>Change state and queue the next messages together.</b> The status update and the outbox
 *       rows commit atomically, so the saga can never be in a state it has not announced, nor
 *       announce a state it is not in.</li>
 * </ol>
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
        if (order.getStatus() != OrderStatus.PENDING) {
            // Already past this step (a redelivery of an earlier reservation, or the customer
            // cancelled first). The reservation itself is still worth remembering: without it the
            // compensation would not know what to release.
            if (order.getReservationId() == null && !order.getStatus().isFinal()) {
                order.rememberReservation(event.reservationId());
                orders.saveAndFlush(order);
            }
            ignored("StockReserved", order);
            return;
        }
        order.markPaymentPending(event.reservationId());
        orders.saveAndFlush(order);
        outbox.record(order.getId(), ProcessPayment.class.getSimpleName(), Topics.PAYMENT_COMMANDS,
                new ProcessPayment(
                        order.getId(), order.getUserId(), order.getTotalAmount(), order.getCurrency()));
        log.info("Order {} has stock reserved ({}), asking for payment of {} {}",
                order.getId(), event.reservationId(), order.getTotalAmount(), order.getCurrency());
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
        if (!OrderStatus.STOCK_HELD.contains(order.getStatus())) {
            // A payment for an order that is no longer waiting for one. Nothing is corrected here:
            // if the order was cancelled meanwhile, its own compensation already asked for the
            // refund, and inventing a second one would refund twice.
            ignored("PaymentCompleted", order);
            return;
        }
        order.markConfirmed(event.paymentId());
        orders.saveAndFlush(order);
        outbox.record(order.getId(), OrderConfirmed.class.getSimpleName(), Topics.ORDER_EVENTS,
                new OrderConfirmed(order.getId(), order.getOrderNumber(), order.getUserId(),
                        order.getTotalAmount(), order.getCurrency()));
        outbox.record(order.getId(), CommitStock.class.getSimpleName(), Topics.INVENTORY_COMMANDS,
                new CommitStock(order.getId()));
        log.info("Order {} confirmed, payment {}", order.getId(), event.paymentId());
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
        if (order.getStatus() != OrderStatus.CANCELLATION_PENDING) {
            ignored("PaymentRefunded", order);
            return;
        }
        String reason = order.getFailureReason() == null ? CANCELLED_BY_CUSTOMER : order.getFailureReason();
        order.markCancelled(reason);
        orders.saveAndFlush(order);
        publishCancelled(order, reason);
        log.info("Order {} cancelled, refund {} completed", order.getId(), event.paymentId());
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
                // inventory-service. The other way round would leak stock.
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
                if (order.getPaymentId() == null) {
                    // Nothing to wait for: no payment was ever recorded on this order.
                    order.markCancelled(CANCELLED_BY_CUSTOMER);
                    orders.saveAndFlush(order);
                    publishCancelled(order, CANCELLED_BY_CUSTOMER);
                }
                else {
                    order.markCancellationPending(CANCELLED_BY_CUSTOMER);
                    orders.saveAndFlush(order);
                    log.info("Order {} is being cancelled, waiting for the refund of payment {}",
                            order.getId(), order.getPaymentId());
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
     * @param orderId order to time out
     * @param reason  what the customer is told
     * @return whether this call changed anything
     */
    @Transactional
    public boolean timeOut(UUID orderId, String reason) {
        Optional<Order> found = orders.findById(orderId);
        if (found.isEmpty()) {
            return false;
        }
        Order order = found.get();
        if (order.getStatus().isFinal()) {
            // It finished between the sweep query and this transaction.
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

    // ------------------------------------------------------------------ internals

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
        log.warn("Ignoring {} for order {}: unexpected in state {}",
                eventName, order.getId(), order.getStatus());
    }

    private void releaseStock(Order order, String reason) {
        outbox.record(order.getId(), ReleaseStock.class.getSimpleName(), Topics.INVENTORY_COMMANDS,
                // The lines are left empty on purpose: inventory-service owns the reservation rows
                // and finds them by order id. Repeating our copy of the lines here would invite the
                // two services to disagree about what was reserved.
                new ReleaseStock(order.getId(), order.getReservationId(), reason, List.of()));
    }

    private void refundPayment(Order order, String reason) {
        if (order.getPaymentId() == null) {
            return;
        }
        outbox.record(order.getId(), RefundPayment.class.getSimpleName(), Topics.PAYMENT_COMMANDS,
                new RefundPayment(order.getId(), order.getPaymentId(), order.getTotalAmount(),
                        order.getCurrency(), reason));
    }

    private void publishCancelled(Order order, String reason) {
        outbox.record(order.getId(), OrderCancelled.class.getSimpleName(), Topics.ORDER_EVENTS,
                new OrderCancelled(order.getId(), order.getOrderNumber(), order.getUserId(), reason));
    }
}
