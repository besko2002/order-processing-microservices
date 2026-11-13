package com.example.orders.order.service;

import com.example.orders.order.api.OrderResponse;

/**
 * Outcome of {@code POST /api/orders}: the order, and whether this particular call is what created
 * it. A replayed {@code Idempotency-Key} produces the same order with {@code created = false}, and
 * the controller turns that into {@code 200} instead of {@code 201}.
 *
 * @param order   the order, new or replayed
 * @param created whether this call created it
 */
public record OrderPlacement(OrderResponse order, boolean created) {
}
