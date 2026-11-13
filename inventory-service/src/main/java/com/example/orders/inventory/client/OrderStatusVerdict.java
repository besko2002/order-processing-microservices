package com.example.orders.inventory.client;

/**
 * What order-service said about one order, reduced to what reconciliation may act on. Everything
 * that is not a positive, well-formed answer naming one of the two immutable terminal states is a
 * refusal: the caller fails closed.
 */
public enum OrderStatusVerdict {

    /** The order exists and is {@code CANCELLED}: nothing will ever reserve or sell its stock. */
    CANCELLED,

    /** The order exists and is {@code REJECTED}: it never held anything it was allowed to keep. */
    REJECTED,

    /**
     * The order exists in a state that is not {@code CANCELLED}/{@code REJECTED}
     * ({@code PENDING}, {@code STOCK_RESERVED}, {@code PAYMENT_PENDING}, {@code CONFIRMED},
     * {@code CANCELLATION_PENDING}): its reservations are legitimate or still in flux.
     */
    ACTIVE,

    /** order-service answered {@code 404}: no such order (or one this caller cannot see). */
    NOT_FOUND,

    /** order-service answered {@code 401} or {@code 403} to the forwarded administrator token. */
    ACCESS_DENIED,

    /**
     * The answer cannot be trusted: a malformed, empty, oversized or non-JSON body, an id that is
     * not the requested one, a missing/unknown/mis-typed status, a redirect or another
     * unexpected status code.
     */
    UNVERIFIABLE,

    /** order-service could not be asked or did not answer in time: connect/read failure, timeout, 5xx, 408, 429. */
    UNAVAILABLE;

    /** @return whether this answer proves the order is in a state in which its holds may be released */
    public boolean permitsRelease() {
        return this == CANCELLED || this == REJECTED;
    }

    /**
     * @return whether this answer says the dependency (or the credentials, or the contract) is
     *         unusable rather than saying something about this one order — repeated, it means the
     *         rest of a batch would fail the same way
     */
    public boolean isDependencyFailure() {
        return this == ACCESS_DENIED || this == UNVERIFIABLE || this == UNAVAILABLE;
    }
}
