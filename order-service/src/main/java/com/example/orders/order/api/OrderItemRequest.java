package com.example.orders.order.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One requested line. The client sends what it wants, never what it thinks it costs — the price
 * is snapshotted from the catalog server-side, so a tampered body cannot buy anything cheaply.
 *
 * @param productId catalog id of the product
 * @param quantity  units wanted, 1..{@value OrderItemRequest#MAX_QUANTITY}
 */
public record OrderItemRequest(

        @NotNull(message = "must be provided")
        UUID productId,

        @Min(value = 1, message = "must be at least 1")
        @Max(value = MAX_QUANTITY, message = "must be at most " + MAX_QUANTITY)
        int quantity) {

    /** Upper bound per product on one order; a retail order, not a wholesale one. */
    public static final int MAX_QUANTITY = 100;
}
