package com.example.orders.payment.domain;

/** Lifecycle of one payment; mirrored by the {@code CHECK} constraint on {@code payments.status}. */
public enum PaymentStatus {

    /**
     * The charge went through. Called authorised rather than captured because a real integration
     * would separate the two, and the saga only ever needs "the money is committed to us".
     */
    AUTHORIZED,

    /** The gateway declined. Terminal: a new attempt would be a new payment for a new order. */
    FAILED,

    /** An authorised charge that was given back after the order was cancelled. */
    REFUNDED
}
