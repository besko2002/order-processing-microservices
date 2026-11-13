package com.example.orders.inventory.api;

import com.example.orders.inventory.domain.Product;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What an administrator sees: the public fields plus the operational ones — how much stock is held
 * by open orders, the optimistic-locking version and the audit timestamps.
 *
 * @param id                product id
 * @param sku               business key
 * @param name              display name
 * @param description       long text, may be {@code null}
 * @param price             unit price
 * @param availableQuantity units a new order may still take
 * @param reservedQuantity  units currently held by open orders
 * @param version           optimistic-locking version
 * @param createdAt         when the product was created
 * @param updatedAt         when it last changed
 */
public record ProductAdminResponse(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        int availableQuantity,
        int reservedQuantity,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param product stored product
     * @return its administrative representation
     */
    public static ProductAdminResponse of(Product product) {
        return new ProductAdminResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getAvailableQuantity(),
                product.getReservedQuantity(),
                product.getVersion(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
