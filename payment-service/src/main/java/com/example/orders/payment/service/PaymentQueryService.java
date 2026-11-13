package com.example.orders.payment.service;

import com.example.orders.payment.api.PaymentResponse;
import com.example.orders.payment.domain.PaymentRepository;
import com.example.orders.support.error.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading payments. There is no write API on purpose: money only ever moves because the saga said
 * so, which means through a command on {@code payment.commands} — never through an HTTP call a
 * client could make.
 */
@Service
public class PaymentQueryService {

    private final PaymentRepository payments;

    PaymentQueryService(PaymentRepository payments) {
        this.payments = payments;
    }

    /**
     * A payment of somebody else's order is reported as {@code 404}, not {@code 403}: a {@code 403}
     * would confirm that the order exists, which is exactly what its owner is entitled to keep.
     *
     * @param orderId  order to look up the payment of
     * @param callerId the authenticated caller
     * @param admin    whether the caller holds {@code ADMIN}
     * @return the payment
     * @throws NotFoundException when there is none, or it belongs to another customer
     */
    @Transactional(readOnly = true)
    public PaymentResponse byOrder(UUID orderId, UUID callerId, boolean admin) {
        return payments.findByOrderId(orderId)
                .filter(payment -> admin || payment.getCustomerId().equals(callerId))
                .map(PaymentResponse::of)
                .orElseThrow(() -> new NotFoundException("No payment exists for order " + orderId));
    }
}
