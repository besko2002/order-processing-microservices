package com.example.orders.inventory.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of {@code PUT /api/inventory/products/{id}}. The SKU and the stock are not here on purpose:
 * the SKU is the business key other services already recorded, and stock moves only through the
 * stock endpoint and through reservations.
 *
 * @param name        display name
 * @param description optional long text; omitting it clears the stored one, as a PUT should
 * @param price       unit price with at most two decimals, never negative
 */
public record UpdateProductRequest(
        @NotBlank(message = "must be provided")
        @Size(max = 200, message = "must be at most 200 characters")
        String name,

        @Size(max = 4000, message = "must be at most 4000 characters")
        String description,

        @NotNull(message = "must be provided")
        @DecimalMin(value = "0.00", message = "must not be negative")
        @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimals")
        BigDecimal price) {
}
