package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Compensation result: the money went back to the customer.
 *
 * <p>order-service waits for this before it calls a cancelled-but-confirmed order finished, so the
 * customer is never told "cancelled" while the charge is still standing.
 *
 * @param orderId   order that was cancelled
 * @param paymentId payment that was refunded
 * @param amount    amount returned
 * @param currency  ISO 4217 code
 * @param reason    why it was refunded
 */
public record PaymentRefunded(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String reason) {
}
