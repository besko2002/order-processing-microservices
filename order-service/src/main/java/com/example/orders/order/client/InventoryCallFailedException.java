package com.example.orders.order.client;

/**
 * One HTTP attempt against inventory-service failed in a way that may well succeed next time: a
 * connect/read timeout, a dropped connection, or a {@code 5xx}.
 *
 * <p>This exact type is what {@code resilience4j} is configured to retry and to record as a
 * circuit-breaker failure. Its parent {@link InventoryUnavailableException} is not, so neither the
 * fallback's verdict nor an open breaker feeds back into the statistics that produced it.
 */
public class InventoryCallFailedException extends InventoryUnavailableException {

    /**
     * @param message safe-to-expose detail
     */
    public InventoryCallFailedException(String message) {
        super(message);
    }

    /**
     * @param message safe-to-expose detail
     * @param cause   transport failure, for the log only
     */
    public InventoryCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
