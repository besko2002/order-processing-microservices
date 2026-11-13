package com.example.orders.order.domain;

import java.util.Set;

/**
 * Where an order stands in the saga. The same names are the {@code CHECK} constraint on
 * {@code orders.status} in {@code V3__saga.sql}, so an unknown value cannot reach the table even
 * through raw SQL.
 *
 * <p>The transitions are driven by events, never by a caller:
 *
 * <pre>
 * PENDING ──StockRejected──────────────────────────────▶ REJECTED
 *    │
 *    └─StockReserved─▶ PAYMENT_PENDING ──PaymentFailed──▶ CANCELLED
 *                            │
 *                            └─PaymentCompleted────────▶ CONFIRMED
 *                                                            │
 *                                        cancel (customer)    ▼
 *                                              CANCELLATION_PENDING
 *                                                            │
 *                                            PaymentRefunded ▼
 *                                                        CANCELLED
 * </pre>
 *
 * <p>Every one of those steps is guarded: an event that arrives for an order in a state the step
 * does not expect is logged and dropped, never applied.
 */
public enum OrderStatus {

    /** Accepted and announced with {@code OrderCreated}; stock and payment have not answered yet. */
    PENDING,

    /**
     * Stock is held for the order.
     *
     * <p>Not produced by the current flow — {@code StockReserved} takes the order straight to
     * {@link #PAYMENT_PENDING}, because reserving and asking for the charge are one transaction.
     * The state is part of the model (and of the {@code CHECK} constraint) so that an operator can
     * park an order between the two steps, and the payment transitions accept it.
     */
    STOCK_RESERVED,

    /** Stock is held and {@code ProcessPayment} is on its way to payment-service. */
    PAYMENT_PENDING,

    /** Stock reserved, payment taken, stock committed. */
    CONFIRMED,

    /**
     * A confirmed order the customer cancelled: {@code RefundPayment} and {@code ReleaseStock} are
     * out, and the order waits for {@code PaymentRefunded} before it calls itself cancelled. The
     * customer is never told "cancelled" while the charge is still standing.
     */
    CANCELLATION_PENDING,

    /** Undone after it had already progressed: payment failed, the customer cancelled, or it timed out. */
    CANCELLED,

    /** Turned down before any money or stock moved, e.g. not enough stock. */
    REJECTED;

    private static final Set<OrderStatus> FINAL = Set.of(CONFIRMED, CANCELLED, REJECTED);

    /** States the saga still expects to leave; the timeout sweeper watches exactly these. */
    public static final Set<OrderStatus> UNFINISHED =
            Set.of(PENDING, STOCK_RESERVED, PAYMENT_PENDING, CANCELLATION_PENDING);

    /** States in which stock may be held and a payment may already exist. */
    public static final Set<OrderStatus> STOCK_HELD = Set.of(STOCK_RESERVED, PAYMENT_PENDING);

    /** @return whether nothing further will happen to an order in this state */
    public boolean isFinal() {
        return FINAL.contains(this);
    }
}
