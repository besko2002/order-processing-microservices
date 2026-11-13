package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Saga step 3 (happy path): the customer was charged. */
public record PaymentCompleted(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency) {
}
