package com.example.orders.order.client;

import com.example.orders.support.error.ServiceUnavailableException;

/**
 * inventory-service could not price this order right now. Rendered as {@code 503}.
 *
 * <p>This is the type the caller sees. It is <em>not</em> retried and <em>not</em> recorded by the
 * circuit breaker — that is {@link InventoryCallFailedException}, its subclass, which is what a
 * single failed HTTP attempt raises. Keeping the two apart is what stops an open breaker from
 * being retried, and stops the fallback's own exception from being counted as yet another failure.
 */
public class InventoryUnavailableException extends ServiceUnavailableException {

    /**
     * @param message safe-to-expose detail
     */
    public InventoryUnavailableException(String message) {
        super(message);
    }

    /**
     * @param message safe-to-expose detail
     * @param cause   transport or breaker failure, for the log only
     */
    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
