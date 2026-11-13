package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Saga step 3 (failure): the charge was declined, compensation starts. */
public record PaymentFailed(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String reason) {
}
