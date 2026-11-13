package com.example.orders.payment.api;

import com.example.orders.payment.domain.Payment;
import com.example.orders.payment.domain.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A payment as the API shows it. {@code version} stays inside — it is a persistence detail.
 *
 * @param id            payment id, the value carried in {@code PaymentCompleted}
 * @param orderId       order that was paid for
 * @param customerId    who was charged
 * @param amount        amount
 * @param currency      ISO 4217 code
 * @param status        where the payment stands
 * @param failureReason why it was declined, or why it was refunded; absent otherwise
 * @param providerRef   the (simulated) provider's own reference
 * @param createdAt     when the charge was attempted
 * @param updatedAt     when it last changed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        UUID id,
        UUID orderId,
        UUID customerId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String failureReason,
        String providerRef,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param payment stored payment
     * @return its public representation
     */
    public static PaymentResponse of(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getCustomerId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getFailureReason(),
                payment.getProviderRef(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
