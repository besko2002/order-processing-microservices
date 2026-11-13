package com.example.orders.order.api;

import com.example.orders.order.domain.OrderLine;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line as it was priced when the order was placed.
 *
 * @param productId   catalog id
 * @param productName name at the time of ordering
 * @param unitPrice   price at the time of ordering
 * @param quantity    units ordered
 * @param lineTotal   {@code unitPrice * quantity}, stored rather than recomputed
 */
public record OrderItemResponse(
        UUID productId,
        String productName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {

    /**
     * @param line stored line
     * @return its public representation
     */
    public static OrderItemResponse of(OrderLine line) {
        return new OrderItemResponse(
                line.getProductId(),
                line.getProductName(),
                line.getUnitPrice(),
                line.getQuantity(),
                line.getLineTotal());
    }
}
