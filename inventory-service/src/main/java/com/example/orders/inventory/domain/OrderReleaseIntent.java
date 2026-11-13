package com.example.orders.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A cancellation that inventory-service heard about before (or without) the reservation it undoes.
 *
 * <p>{@code OrderCreated} and {@code ReleaseStock} travel on different Kafka topics, so Kafka gives
 * no ordering guarantee between them. When a release lands first, there is no reservation row to
 * hand back — that used to be only an UNKNOWN_ORDER log line, and the {@code OrderCreated} that
 * followed could still hold stock for a cancelled order. This row makes the release <em>intent</em>
 * durable: while it exists, an {@code OrderCreated} for that order id is answered with
 * {@code StockRejected} instead of reserving. The saga compensates on arrival, and no stock ever
 * gets held.
 *
 * <p>There is no state machine here and none is needed. The intent is written permanently: an order
 * never un-cancels, so nothing can ever make reserving for it correct again, and every duplicate
 * release or redelivered {@code OrderCreated} idempotently hits the same rejection. {@link
 * ReservationStatus#RESERVED} rows of unrelated orders are untouched — the row is keyed by order id,
 * holds no stock, and exists only if a release for that exact order was consumed here.
 */
@Entity
@Table(name = "order_release_intents")
public class OrderReleaseIntent {

    @Id
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected OrderReleaseIntent() {
        // for JPA
    }

    private OrderReleaseIntent(UUID orderId, String reason) {
        this.orderId = orderId;
        this.reason = reason;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    /**
     * @param orderId cancelled order whose stock must never be reserved again by this service
     * @param reason  what the cancellation said
     * @return the intent row; it is inserted with {@code ON CONFLICT DO NOTHING}, so the first
     *         release's reason wins and re-releases change nothing
     */
    public static OrderReleaseIntent of(UUID orderId, String reason) {
        return new OrderReleaseIntent(orderId, reason);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getReason() {
        return reason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
