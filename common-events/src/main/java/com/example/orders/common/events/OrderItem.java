package com.example.orders.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of an order, repeated in the stock-related events. */
public record OrderItem(
        UUID productId,
        String sku,
        int quantity,
        BigDecimal unitPrice) {
}
