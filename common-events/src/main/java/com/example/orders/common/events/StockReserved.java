package com.example.orders.common.events;

import java.util.List;
import java.util.UUID;

/** Saga step 2 (happy path): inventory-service reserved every requested item. */
public record StockReserved(
        UUID orderId,
        UUID reservationId,
        List<OrderItem> items) {
}
