package com.example.orders.inventory.api;

import com.example.orders.inventory.domain.Product;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What the public catalog shows: enough to browse and to build an order, and nothing about how
 * much stock other customers are holding.
 *
 * @param id                product id, used as {@code productId} when ordering
 * @param sku               business key
 * @param name              display name
 * @param description       long text, may be {@code null}
 * @param price             unit price
 * @param availableQuantity units a new order may still take
 */
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        int availableQuantity) {

    /**
     * @param product stored product
     * @return its public representation
     */
    public static ProductResponse of(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getAvailableQuantity());
    }
}
