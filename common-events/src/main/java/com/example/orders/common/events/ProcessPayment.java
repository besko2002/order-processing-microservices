package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Saga step 3 (command): order-service asks payment-service to charge the customer once stock is
 * held.
 *
 * <p>A command, not an event: it names one owner service and one thing that should happen. It
 * carries everything payment-service needs, because payment-service never reads order_db —
 * {@code orderId} is both the correlation and the idempotency key of the charge.
 *
 * @param orderId    order being paid for; also the Kafka message key
 * @param customerId who is charged, so payment-service can authorise the visibility endpoint
 * @param amount     amount to charge
 * @param currency   ISO 4217 code
 */
public record ProcessPayment(
        UUID orderId,
        UUID customerId,
        BigDecimal amount,
        String currency) {
}
