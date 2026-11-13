package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Saga step 1: an order was accepted in {@code PENDING} state. */
public record OrderCreated(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        BigDecimal totalAmount,
        String currency,
        List<OrderItem> items) {
}
