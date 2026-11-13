package com.example.orders.inventory.domain;

/** Lifecycle of one reserved order line; mirrored by the CHECK constraint on the table. */
public enum ReservationStatus {

    /** Stock is being held for the order: it left {@code available} but has not been sold yet. */
    RESERVED,

    /** The hold was given back (order rejected, payment failed): the stock is on the shelf again. */
    RELEASED,

    /** The sale went through: the units are gone for good. */
    COMMITTED
}
