package com.example.orders.inventory.api;

import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.StockReservation;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One reserved order line, as shown by {@code GET /api/inventory/reservations}.
 *
 * @param id        reservation row id
 * @param orderId   order holding the stock
 * @param productId product being held
 * @param sku       its SKU, {@code null} if the product was deleted meanwhile
 * @param quantity  units held
 * @param status    where the hold stands
 * @param createdAt when the hold was taken
 * @param updatedAt when it last changed status
 */
public record ReservationResponse(
        UUID id,
        UUID orderId,
        UUID productId,
        String sku,
        int quantity,
        ReservationStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param reservation stored reservation
     * @param sku         SKU of the referenced product, may be {@code null}
     * @return its representation
     */
    public static ReservationResponse of(StockReservation reservation, String sku) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getOrderId(),
                reservation.getProductId(),
                sku,
                reservation.getQuantity(),
                reservation.getStatus(),
                reservation.getCreatedAt(),
                reservation.getUpdatedAt());
    }
}
