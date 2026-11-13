package com.example.orders.inventory.service;

/**
 * What one order's reconciliation did — or why it did nothing.
 *
 * <p>Reconciliation is the operator tool for holds that leaked <em>before</em> the
 * release-intent fix: a cancelled order whose {@code StockReserved} was processed despite the
 * cancellation (the orchestrator then drops it) and whose release command therefore found nothing
 * or arrived too late. It is deliberately conservative — see {@code ReservationReconciliation}.
 *
 * <p>The first four values are what was done to an order order-service confirmed as
 * {@code CANCELLED}/{@code REJECTED}. Every {@code REFUSED_*}/{@code SKIPPED_VERIFICATION_*}
 * value means inventory did <em>not</em> learn that from order-service, and therefore changed
 * nothing at all for that id: no reservation, no stock counter, no release intent, no outbox row.
 */
public enum ReconciliationOutcome {

    /** The order still held {@code RESERVED} stock; it was given back to the shelf. */
    RELEASED,

    /** Every row was already released; nothing moved (re-running reconciliation is safe). */
    ALREADY_RELEASED,

    /** No reservation was ever recorded for this order id; the intent is still recorded. */
    NO_RESERVATIONS,

    /**
     * The order has {@code COMMITTED} rows, i.e. stock that was actually sold (a confirmed order or
     * a refunded sale). Reconciliation refuses to touch it so a typo'd order id can never turn a
     * legitimate sale into merchandise; the refund flow does that job through its own
     * {@code ReleaseStock} command.
     */
    SKIPPED_COMMITTED,

    /**
     * order-service says the order is in a state that is neither {@code CANCELLED} nor
     * {@code REJECTED} (including {@code CONFIRMED}): its reservations are legitimate or still in
     * flux. Nothing was touched — not even a release intent for an order that has not reserved yet.
     */
    REFUSED_ACTIVE_ORDER,

    /** order-service does not know the order ({@code 404}); nothing was touched. */
    REFUSED_ORDER_NOT_FOUND,

    /** order-service refused the administrator's token ({@code 401}/{@code 403}); nothing was touched. */
    REFUSED_ACCESS_DENIED,

    /**
     * order-service answered, but not with anything that can be trusted (malformed body, another
     * order's id, an unknown or missing status, an unexpected status code); nothing was touched.
     */
    REFUSED_UNVERIFIABLE,

    /** order-service could not be reached or timed out or failed ({@code 5xx}); nothing was touched. */
    REFUSED_VERIFICATION_UNAVAILABLE,

    /**
     * Not even asked: earlier orders of the same run showed order-service (or the token, or the
     * contract) unusable several times in a row, so the rest of the batch was not sent into the
     * same failure. Nothing was touched; run again once the dependency is healthy.
     */
    SKIPPED_VERIFICATION_UNAVAILABLE
}
