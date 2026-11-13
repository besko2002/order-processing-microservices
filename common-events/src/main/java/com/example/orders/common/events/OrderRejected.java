package com.example.orders.common.events;

import java.util.UUID;

/**
 * Terminal state reached before anything had to be undone — today only "stock said no".
 *
 * <p>Kept apart from {@link OrderCancelled} on purpose: a rejection means no money and no stock
 * ever moved, while a cancellation means the saga had to compensate. Downstream consumers
 * (notifications, reporting) want to say very different things about the two.
 *
 * @param orderId     order that was turned down
 * @param orderNumber short human-facing reference
 * @param customerId  owner of the order
 * @param reason      why it was turned down
 */
public record OrderRejected(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String reason) {
}
