package com.example.orders.inventory.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reservations are always looked up per order (saga steps) or per product (delete checks). */
public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    List<StockReservation> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    List<StockReservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);

    boolean existsByProductIdAndStatus(UUID productId, ReservationStatus status);

    void deleteByProductId(UUID productId);
}
