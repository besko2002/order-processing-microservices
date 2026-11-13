package com.example.orders.support.error;

/**
 * The request is well formed but clashes with the current state — a duplicate business key, a
 * stock correction that would go negative, a delete blocked by live references. Rendered as
 * {@code 409}.
 */
public class ConflictException extends RuntimeException {

    /**
     * @param message safe-to-expose detail explaining what the request collided with
     */
    public ConflictException(String message) {
        super(message);
    }
}
