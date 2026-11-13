package com.example.orders.inventory.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /api/inventory/reservations/reconcile}. The caller names the exact orders
 * whose leaked holds should be recovered. The names are only <em>candidates</em>: the server asks
 * order-service for each order's state and acts only on {@code CANCELLED} / {@code REJECTED}
 * (see {@code ReservationReconciliation}); nothing in this body — not the ids, not the reason — is
 * accepted as proof that an order is finished. A way to list candidates is
 *
 * <pre>{@code
 * SELECT id, status, updated_at FROM orders WHERE status IN ('CANCELLED', 'REJECTED') ORDER BY updated_at DESC;
 * }</pre>
 *
 * <p>{@code reason} is stored with the release intents the run creates, for the audit trail.
 *
 * @param orderIds at least one, at most 200 order ids, none {@code null}; duplicates are collapsed
 * @param reason   optional why; a sensible default is filled in when blank
 */
public record ReconciliationRequest(
        @NotEmpty @Size(max = 200) List<@NotNull UUID> orderIds,
        @Size(max = 500) String reason) {

    /** @return the distinct ids, first occurrence first — the report keeps this order */
    public List<UUID> distinctOrderIds() {
        return orderIds.stream().distinct().toList();
    }
}
