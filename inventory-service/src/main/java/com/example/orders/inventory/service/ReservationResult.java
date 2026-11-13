package com.example.orders.inventory.service;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of one {@link StockReservationService#reserve} call — the phase-6 Kafka consumer turns
 * this into either a {@code StockReserved} or a {@code StockRejected} event.
 *
 * @param orderId   order the reservation was requested for
 * @param outcome   what happened
 * @param shortages why it was rejected; empty unless {@code outcome} is {@link Outcome#REJECTED}
 */
public record ReservationResult(UUID orderId, Outcome outcome, List<Shortage> shortages) {

    /** What a reserve call did. */
    public enum Outcome {

        /** Every line was reserved by this call. */
        RESERVED,

        /** This order was already reserved earlier; nothing was changed a second time. */
        ALREADY_RESERVED,

        /** Nothing was reserved: at least one line could not be satisfied. */
        REJECTED
    }

    /** Why one line could not be reserved. */
    public enum Reason {

        /** No product with that id exists in this catalog. */
        PRODUCT_NOT_FOUND,

        /** The product exists but does not have enough available stock. */
        INSUFFICIENT_STOCK
    }

    /**
     * @param productId product of the failing line
     * @param sku       its SKU, {@code null} when the product does not exist
     * @param reason    what was wrong
     * @param requested units asked for
     * @param available units that were available, {@code 0} for an unknown product
     */
    public record Shortage(UUID productId, String sku, Reason reason, int requested, int available) {
    }

    /**
     * @param orderId order that is now holding stock
     * @return a successful result
     */
    public static ReservationResult reserved(UUID orderId) {
        return new ReservationResult(orderId, Outcome.RESERVED, List.of());
    }

    /**
     * @param orderId order that already had reservation rows
     * @return an idempotent no-op result
     */
    public static ReservationResult alreadyReserved(UUID orderId) {
        return new ReservationResult(orderId, Outcome.ALREADY_RESERVED, List.of());
    }

    /**
     * @param orderId   order that gets nothing
     * @param shortages one entry per line that could not be satisfied
     * @return a rejection
     */
    public static ReservationResult rejected(UUID orderId, List<Shortage> shortages) {
        return new ReservationResult(orderId, Outcome.REJECTED, List.copyOf(shortages));
    }

    /**
     * @return whether the order holds stock after this call, whether or not this call is what
     *         reserved it
     */
    public boolean isHoldingStock() {
        return outcome == Outcome.RESERVED || outcome == Outcome.ALREADY_RESERVED;
    }
}
