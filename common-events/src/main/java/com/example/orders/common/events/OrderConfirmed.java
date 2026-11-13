package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Saga step 4: stock reserved and payment completed, the order is final. */
public record OrderConfirmed(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        BigDecimal totalAmount,
        String currency) {
}
