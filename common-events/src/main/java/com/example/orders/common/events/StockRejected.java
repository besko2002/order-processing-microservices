package com.example.orders.common.events;

import java.util.List;
import java.util.UUID;

/** Saga step 2 (failure): stock could not be reserved, the order is rejected. */
public record StockRejected(
        UUID orderId,
        String reason,
        List<String> unavailableSkus) {
}
