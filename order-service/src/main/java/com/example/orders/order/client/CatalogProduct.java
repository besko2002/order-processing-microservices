package com.example.orders.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The slice of inventory-service's {@code GET /api/products/{id}} response this service cares
 * about. Unknown fields are ignored on purpose: the catalog may grow new ones without forcing a
 * release here.
 *
 * @param id    product id
 * @param sku   business key, copied into the {@code OrderCreated} event
 * @param name  display name, snapshotted onto the order line
 * @param price unit price at the moment of the call
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogProduct(
        UUID id,
        String sku,
        String name,
        BigDecimal price) {
}
