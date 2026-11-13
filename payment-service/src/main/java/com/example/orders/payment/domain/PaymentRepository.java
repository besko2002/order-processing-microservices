package com.example.orders.payment.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Payments are always addressed by the order they belong to. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * @param orderId order the payment belongs to
     * @return the single payment of that order, if there is one
     */
    Optional<Payment> findByOrderId(UUID orderId);
}
