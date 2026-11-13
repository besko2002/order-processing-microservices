package com.example.orders.common.events;

import java.util.UUID;

/** Terminal state after a rejection, a payment failure or a customer cancellation. */
public record OrderCancelled(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String reason) {
}
