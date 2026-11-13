package com.example.orders.order.saga;

import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.PaymentFailed;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.StockRejected;
import com.example.orders.common.events.StockReserved;
import com.example.orders.order.api.OrderResponse;
import com.example.orders.support.tx.OptimisticRetry;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The saga's entry points, each one a bounded retry around a transaction of
 * {@link OrderSagaTransactions}.
 *
 * <p>The retry is here and not in the transactions because an optimistic-locking conflict means
 * another transaction changed the order first: the only correct response is to read the new state
 * and decide again — which is exactly what re-running the guarded transaction does. It happens for
 * real when a customer cancels at the same moment a payment answer arrives.
 *
 * <p>After the last attempt the conflict is rethrown. For a Kafka listener that means the record is
 * retried by the container and, if it keeps failing, dead-lettered; for the cancel endpoint it
 * becomes a {@code 409}.
 */
@Service
public class OrderSaga {

    private final OrderSagaTransactions transactions;
    private final int maxAttempts;

    /**
     * @param transactions the transactional unit of work, retried as a whole
     * @param maxAttempts  attempts per event before a locking conflict is given up on
     */
    OrderSaga(
            OrderSagaTransactions transactions,
            @Value("${orders.saga.max-attempts:3}") int maxAttempts) {
        this.transactions = transactions;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param eventId event id, de-duplicated against {@code processed_events}
     * @param event   the payload
     */
    public void stockReserved(UUID eventId, StockReserved event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onStockReserved(eventId, event);
            return null;
        });
    }

    /**
     * @param eventId event id
     * @param event   the payload
     */
    public void stockRejected(UUID eventId, StockRejected event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onStockRejected(eventId, event);
            return null;
        });
    }

    /**
     * @param eventId event id
     * @param event   the payload
     */
    public void paymentCompleted(UUID eventId, PaymentCompleted event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onPaymentCompleted(eventId, event);
            return null;
        });
    }

    /**
     * @param eventId event id
     * @param event   the payload
     */
    public void paymentFailed(UUID eventId, PaymentFailed event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onPaymentFailed(eventId, event);
            return null;
        });
    }

    /**
     * @param eventId event id
     * @param event   the payload
     */
    public void paymentRefunded(UUID eventId, PaymentRefunded event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onPaymentRefunded(eventId, event);
            return null;
        });
    }

    /**
     * @param orderId  order to cancel
     * @param callerId authenticated caller
     * @param admin    whether the caller holds {@code ADMIN}
     * @return the order after the cancellation was started
     */
    public OrderResponse cancel(UUID orderId, UUID callerId, boolean admin) {
        return OptimisticRetry.execute(maxAttempts, () -> transactions.cancelOnRequest(orderId, callerId, admin));
    }

    /**
     * @param orderId order the sweeper gave up on
     * @param reason  what the customer is told
     * @return whether this call changed anything
     */
    public boolean timeOut(UUID orderId, String reason) {
        return OptimisticRetry.execute(maxAttempts, () -> transactions.timeOut(orderId, reason));
    }

    /**
     * @param orderId   order still owed the refund of its tracked payment
     * @param reason    what the re-commanded payload shows
     * @param dueBefore refunds last commanded at or before this instant are due
     * @return whether this call re-queued the refund
     */
    public boolean recoverOutstandingRefund(UUID orderId, String reason, OffsetDateTime dueBefore) {
        return OptimisticRetry.execute(maxAttempts,
                () -> transactions.recoverOutstandingRefund(orderId, reason, dueBefore));
    }
}
