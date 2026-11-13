package com.example.orders.order.api;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An order as the API shows it. {@code version} and {@code idempotencyKey} stay inside: one is a
 * persistence detail, the other is the client's own bookkeeping echoed back to nobody.
 *
 * @param id            order id
 * @param orderNumber   short human-facing reference derived from the id
 * @param userId        owner
 * @param status        where the order stands in the saga
 * @param totalAmount   sum of the line totals
 * @param currency      ISO 4217 code
 * @param failureReason why the order was rejected or cancelled; absent while it is fine
 * @param createdAt     when it was accepted
 * @param updatedAt     last status change
 * @param items         the priced lines
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
        UUID id,
        String orderNumber,
        UUID userId,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<OrderItemResponse> items) {

    /**
     * @param order stored order; its lines must already be loaded
     * @return its public representation
     */
    public static OrderResponse of(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getItems().stream().map(OrderItemResponse::of).toList());
    }
}
