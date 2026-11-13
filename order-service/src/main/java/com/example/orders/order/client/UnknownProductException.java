package com.example.orders.order.client;

import com.example.orders.support.error.BadRequestException;
import java.util.UUID;

/**
 * The catalog answered {@code 404} for a product the request asked for: the caller's fault, so
 * {@code 400} naming the id — and never a circuit-breaker failure, because inventory-service did
 * its job perfectly.
 */
public class UnknownProductException extends BadRequestException {

    private final UUID productId;

    /**
     * @param productId the id inventory-service does not know
     */
    public UnknownProductException(UUID productId) {
        super("Unknown product: " + productId);
        this.productId = productId;
    }

    /** @return the id that was not found */
    public UUID getProductId() {
        return productId;
    }
}
