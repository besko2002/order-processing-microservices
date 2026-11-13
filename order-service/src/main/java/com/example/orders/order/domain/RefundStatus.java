package com.example.orders.order.domain;

/**
 * Whether a refund for this order's payment is outstanding, and how far it got.
 *
 * <p>Order status alone cannot carry this: `{@code CANCELLED}` is a final state, yet a payment that
 * completed <em>after</em> the cancellation still owes the customer their money. The saga has to
 * remember an in-flight refund across a final status, so it gets its own column — one that a status
 * transition never resets:
 *
 * <p>{@code NONE} — nothing to refund (nothing was charged, or the refund is fully settled and its
 * completion is already accounted for). In practice once a payment has been tracked, an order
 * progresses to {@code REQUESTED} and ends in {@code REFUNDED}; {@code NONE} simply means the saga
 * owes this order no refund right now. {@code REQUESTED} — a {@code RefundPayment} command was
 * queued in the same transaction that flipped the flag, so "refund requested" can never be a lie or
 * get lost. Any duplicate answer or retry sees {@code REQUESTED} and refrains from queueing a second
 * command. {@code REFUNDED} — a {@code PaymentRefunded} event for the tracked payment has been
 * applied; renewing the flag this way makes redelivered refund events idempotent even though their
 * event ids differ.
 */
public enum RefundStatus {

    /** No refund is owed by the saga at this moment. */
    NONE,

    /** A {@code RefundPayment} command was queued and its completion has not been heard of yet. */
    REQUESTED,

    /** The tracked payment was refunded and the confirmation is applied. */
    REFUNDED
}
