package com.example.orders.inventory.api;

import com.example.orders.inventory.service.ReconciliationOutcome;
import java.util.List;
import java.util.UUID;

/**
 * What one reconciliation run did, one entry per requested order id, in the order the ids were
 * passed. The call is idempotent: re-running a completed list answers
 * {@code ALREADY_RELEASED}/{@code NO_RESERVATIONS} without moving anything. An order that
 * order-service did not confirm as cancelled or rejected is reported with a {@code REFUSED_*} or
 * {@code SKIPPED_VERIFICATION_*} outcome and was not touched.
 *
 * @param entries one line per distinct requested order id
 */
public record ReconciliationResponse(List<Entry> entries) {

    /**
     * @param orderId order reconciled
     * @param outcome what was done to its rows and what was refused
     */
    public record Entry(UUID orderId, ReconciliationOutcome outcome) {
    }
}
