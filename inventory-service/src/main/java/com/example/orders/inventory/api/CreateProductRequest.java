package com.example.orders.inventory.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of {@code POST /api/inventory/products}.
 *
 * @param sku               business key: letters, digits, dot, underscore and hyphen, up to 64
 *                          characters, unique across the catalog
 * @param name              display name
 * @param description       optional long text
 * @param price             unit price with at most two decimals, never negative
 * @param availableQuantity starting stock, never negative
 */
public record CreateProductRequest(
        @NotBlank(message = "must be provided")
        @Size(max = 64, message = "must be at most 64 characters")
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "may only contain letters, digits, '.', '_' and '-'")
        String sku,

        @NotBlank(message = "must be provided")
        @Size(max = 200, message = "must be at most 200 characters")
        String name,

        @Size(max = 4000, message = "must be at most 4000 characters")
        String description,

        @NotNull(message = "must be provided")
        @DecimalMin(value = "0.00", message = "must not be negative")
        @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimals")
        BigDecimal price,

        @NotNull(message = "must be provided")
        @Min(value = 0, message = "must not be negative")
        Integer availableQuantity) {
}
