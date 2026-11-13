package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Compensation command: refund a settled payment after a cancellation. */
public record RefundPayment(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String reason) {
}
