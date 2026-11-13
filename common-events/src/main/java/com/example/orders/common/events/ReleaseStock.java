package com.example.orders.common.events;

import java.util.List;
import java.util.UUID;

/** Compensation command: give the reserved items back to inventory. */
public record ReleaseStock(
        UUID orderId,
        UUID reservationId,
        String reason,
        List<OrderItem> items) {
}
