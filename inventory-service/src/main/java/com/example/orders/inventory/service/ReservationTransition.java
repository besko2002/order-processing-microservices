package com.example.orders.inventory.service;

/** Outcome of {@link StockReservationService#release} and {@link StockReservationService#commit}. */
public enum ReservationTransition {

    /** This call moved the order's {@code RESERVED} lines to their new status. */
    APPLIED,

    /**
     * The order exists but had no {@code RESERVED} lines left, so there was nothing to do. A
     * redelivered event lands here — that is what makes these operations idempotent.
     */
    ALREADY_APPLIED,

    /** No reservation was ever recorded for that order id. */
    UNKNOWN_ORDER
}
