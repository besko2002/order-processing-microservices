package com.example.orders.support.error;

/**
 * A dependency this request needed is not answering right now — a downstream service is down, a
 * call timed out, or a circuit breaker is open. Rendered as {@code 503}.
 *
 * <p>Deliberately separate from the {@code 500} catch-all: nothing is wrong with the request and
 * nothing is broken inside this service, so the caller (and any proxy in front of it) may retry
 * the very same call in a moment.
 */
public class ServiceUnavailableException extends RuntimeException {

    /**
     * @param message safe-to-expose detail naming the dependency, never its internals
     */
    public ServiceUnavailableException(String message) {
        super(message);
    }

    /**
     * @param message safe-to-expose detail naming the dependency
     * @param cause   the transport or breaker failure, kept for the log only
     */
    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
