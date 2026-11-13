package com.example.orders.inventory.api;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/inventory/products/{id}/stock}: a relative change, not an absolute
 * count. Relative on purpose — "add the 20 units that just arrived" is safe to send twice as
 * concurrent edits go, whereas "set stock to 20" silently discards whatever happened in between.
 *
 * @param delta units to add (restock) or remove (correction); {@code 0} is accepted as a no-op
 */
public record StockAdjustmentRequest(@NotNull(message = "must be provided") Integer delta) {
}
