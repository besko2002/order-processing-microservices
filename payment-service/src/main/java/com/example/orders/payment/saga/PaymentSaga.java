package com.example.orders.payment.saga;

import com.example.orders.common.events.ProcessPayment;
import com.example.orders.common.events.RefundPayment;
import com.example.orders.support.tx.OptimisticRetry;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Entry points of payment-service's saga steps, each a bounded retry around one transaction.
 *
 * <p>A payment row carries a {@code @Version}, so a refund arriving while a redelivered charge is
 * being handled can lose the race; replaying the guarded transaction on fresh data then reaches the
 * right answer. After the last attempt the failure propagates and the record is retried and finally
 * dead-lettered by the container.
 */
@Service
public class PaymentSaga {

    private final PaymentSagaTransactions transactions;
    private final int maxAttempts;

    PaymentSaga(
            PaymentSagaTransactions transactions,
            @Value("${payments.max-attempts:3}") int maxAttempts) {
        this.transactions = transactions;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param eventId event id, de-duplicated against {@code processed_events}
     * @param command the charge command
     */
    public void processPayment(UUID eventId, ProcessPayment command) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onProcessPayment(eventId, command);
            return null;
        });
    }

    /**
     * @param eventId event id
     * @param command the refund command
     */
    public void refundPayment(UUID eventId, RefundPayment command) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onRefundPayment(eventId, command);
            return null;
        });
    }
}
