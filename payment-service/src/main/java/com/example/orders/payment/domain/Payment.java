package com.example.orders.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One payment attempt for one order, mapped to {@code payments} from
 * {@code V2__create_payments.sql}.
 *
 * <p>There is at most one row per order — {@code order_id} is {@code UNIQUE} — and that is the
 * service's contract with the saga: however often {@code ProcessPayment} is delivered, the customer
 * is charged once. The outcome of the first attempt is what every later delivery reports back.
 *
 * <p>The customer id is copied from the command rather than looked up: payment-service does not read
 * order_db, and it still has to be able to tell whether the caller of
 * {@code GET /api/payments/order/{orderId}} owns the payment.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false, unique = true)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Payment() {
        // for JPA
    }

    private Payment(UUID orderId, UUID customerId, BigDecimal amount, String currency,
            PaymentStatus status, String failureReason, String providerRef) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.currency = currency;
        this.status = status;
        this.failureReason = trim(failureReason);
        this.providerRef = providerRef;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    /**
     * @param orderId     order that was paid for
     * @param customerId  who was charged
     * @param amount      amount charged
     * @param currency    ISO 4217 code
     * @param providerRef the gateway's own reference
     * @return a successful payment
     */
    public static Payment authorized(
            UUID orderId, UUID customerId, BigDecimal amount, String currency, String providerRef) {
        return new Payment(orderId, customerId, amount, currency, PaymentStatus.AUTHORIZED, null, providerRef);
    }

    /**
     * @param orderId    order that was not paid for
     * @param customerId who would have been charged
     * @param amount     amount that was declined
     * @param currency   ISO 4217 code
     * @param reason     why the gateway declined
     * @return a failed payment; the row is kept so a redelivery reports the same outcome
     */
    public static Payment failed(
            UUID orderId, UUID customerId, BigDecimal amount, String currency, String reason) {
        return new Payment(orderId, customerId, amount, currency, PaymentStatus.FAILED, reason, null);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Gives an authorised charge back.
     *
     * @param reason why the money is going back
     * @return whether this call changed anything; {@code false} for an already refunded or failed
     *         payment, which is what makes a redelivered {@code RefundPayment} a no-op
     */
    public boolean refund(String reason) {
        if (this.status != PaymentStatus.AUTHORIZED) {
            return false;
        }
        this.status = PaymentStatus.REFUNDED;
        this.failureReason = trim(reason);
        return true;
    }

    /** @return whether the money is currently ours */
    public boolean isAuthorized() {
        return status == PaymentStatus.AUTHORIZED;
    }

    private static String trim(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public long getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
