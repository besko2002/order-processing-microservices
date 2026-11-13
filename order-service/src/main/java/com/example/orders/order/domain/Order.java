package com.example.orders.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * An order and its priced lines, mapped to the {@code orders} table from
 * {@code V2__create_orders.sql} (Flyway owns the schema; {@code ddl-auto=validate} only checks
 * that this mapping still matches it).
 *
 * <p>The entity name is {@code OrderAggregate} rather than {@code Order}: {@code order} is a
 * keyword in the query language, and giving the entity a name that never has to be quoted keeps
 * every derived query unambiguous. The table is still {@code orders}.
 *
 * <p>The total is the sum of the stored line totals, computed once at creation. Nothing
 * recalculates it from the catalog later — see {@link OrderLine} for why.
 */
@Entity(name = "OrderAggregate")
@Table(name = "orders")
public class Order {

    /** Currency every order is priced in for now; the catalog has no multi-currency prices. */
    public static final String DEFAULT_CURRENCY = "EUR";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Owner of the order: the {@code sub} claim of the token that created it. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * The reservation inventory-service took for this order, learned from {@code StockReserved}.
     * A plain column, not a foreign key: the row lives in another service's database, and this
     * service can only ask for it to be released.
     */
    @Column(name = "reservation_id")
    private UUID reservationId;

    /** The payment payment-service recorded, learned from {@code PaymentCompleted}. */
    @Column(name = "payment_id")
    private UUID paymentId;

    /**
     * The saga's own view of the refund for {@link #paymentId}. Kept separately from the status
     * because a cancelled order is final and can still owe the customer their money — see
     * {@link RefundStatus} for why.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 20)
    private RefundStatus refundStatus = RefundStatus.NONE;

    /**
     * When the refund of {@link #paymentId} was last commanded (the first request or a recovery
     * re-queue, whichever is more recent). The recovery sweep reads it as the base for its retry
     * interval, so a lost refund is re-commanded on a deliberate cadence instead of on every
     * scheduler tick; see {@code V6__refund_recovery.sql}.
     */
    @Column(name = "refund_retry_at")
    private OffsetDateTime refundRetryAt;

    /** Optimistic-locking guard: two saga events for one order cannot both win. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Lines are part of the order, never addressed on their own, so they are cascaded and
     * orphan-removed: the aggregate is the only way in.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("productName ASC")
    private List<OrderLine> items = new ArrayList<>();

    protected Order() {
        // for JPA
    }

    private Order(UUID userId, String currency, String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.status = OrderStatus.PENDING;
        this.totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    /**
     * @param userId         owner, taken from the token
     * @param currency       ISO 4217 code, three letters
     * @param idempotencyKey replay guard supplied by the client, may be {@code null}
     * @return an empty {@code PENDING} order; add its lines with {@link #addLine}
     */
    public static Order pending(UUID userId, String currency, String idempotencyKey) {
        return new Order(userId, currency, idempotencyKey);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Appends a priced line and folds its total into the order total.
     *
     * @param productId   catalog id
     * @param productName name as the catalog had it when the order was placed
     * @param unitPrice   price as the catalog had it when the order was placed
     * @param quantity    units ordered, positive
     * @return the stored line
     */
    public OrderLine addLine(UUID productId, String productName, BigDecimal unitPrice, int quantity) {
        OrderLine line = new OrderLine(this, productId, productName, unitPrice, quantity);
        this.items.add(line);
        this.totalAmount = this.totalAmount.add(line.getLineTotal());
        return line;
    }

    // ------------------------------------------------------------------ saga transitions
    //
    // Plain state mutators: *whether* a transition is allowed is decided by the saga, which knows
    // the event it is reacting to and can log an unexpected one instead of applying it. Putting the
    // guard here as well would only duplicate that decision in a place with less context.

    /**
     * Stock is held and the charge has been requested.
     *
     * @param reservationId reservation inventory-service created, may be {@code null} if unknown
     */
    public void markPaymentPending(UUID reservationId) {
        this.status = OrderStatus.PAYMENT_PENDING;
        this.reservationId = reservationId;
        this.failureReason = null;
    }

    /**
     * The charge went through.
     *
     * @param paymentId payment payment-service recorded
     */
    public void markConfirmed(UUID paymentId) {
        this.status = OrderStatus.CONFIRMED;
        this.paymentId = paymentId;
        this.failureReason = null;
    }

    /**
     * Turned down before anything moved.
     *
     * @param reason why, shown to the customer
     */
    public void markRejected(String reason) {
        this.status = OrderStatus.REJECTED;
        this.failureReason = trim(reason);
    }

    /**
     * Undone after it had progressed.
     *
     * @param reason why, shown to the customer
     */
    public void markCancelled(String reason) {
        this.status = OrderStatus.CANCELLED;
        this.failureReason = trim(reason);
    }

    /**
     * Cancellation accepted, waiting for the refund to come back.
     *
     * @param reason why, shown to the customer
     */
    public void markCancellationPending(String reason) {
        this.status = OrderStatus.CANCELLATION_PENDING;
        this.failureReason = trim(reason);
    }

    /**
     * @param reservationId reservation this order holds
     */
    public void rememberReservation(UUID reservationId) {
        this.reservationId = reservationId;
    }

    /**
     * Records the id of a payment whose answer arrived, without touching the status. Used when the
     * answer is <em>late</em> (the saga already cancelled the order): the payment still happened, so
     * the refund of exactly this payment has to be trackable — but {@code markConfirmed} would be
     * wrong, as it would re-confirm a cancelled order.
     *
     * @param paymentId payment payment-service recorded
     */
    public void rememberPayment(UUID paymentId) {
        this.paymentId = paymentId;
    }

    /**
     * Marks a {@code RefundPayment} command as queued; callers queue the command in the same
     * transaction, so the flag can never claim a refund that is not actually on its way.
     *
     * @return whether this call was the one that requested the refund ({@code false} means a
     *         refund of the tracked payment is already requested or settled, and queueing another
     *         command would duplicate the effect)
     */
    public boolean requestRefund() {
        if (this.refundStatus != RefundStatus.NONE) {
            return false;
        }
        this.refundStatus = RefundStatus.REQUESTED;
        this.refundRetryAt = OffsetDateTime.now(ZoneOffset.UTC);
        return true;
    }

    /** Applies a {@code PaymentRefunded} event: the tracked refund is finished. */
    public void markRefunded() {
        this.refundStatus = RefundStatus.REFUNDED;
    }

    /**
     * Records that the refund was (re-)commanded just now. {@code requestRefund()} stamps the
     * first attempt; every recovery re-queue calls this so the next attempt waits one retry
     * interval. The caller writes the outbox row in the same transaction, so the timestamp never
     * claims an attempt that was not queued.
     *
     * @param now the moment of this attempt
     */
    public void bookRefundRetry(OffsetDateTime now) {
        this.refundRetryAt = now;
    }

    private static String trim(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    /**
     * @return a short human-facing reference derived from the id, e.g. {@code ORD-3F2A1B7C}. It is
     *         not stored: the id already is the business key, and a second unique number would be
     *         one more thing to keep consistent for no gain.
     */
    public String getOrderNumber() {
        return orderNumberOf(this.id);
    }

    /**
     * @param orderId an order id
     * @return the same reference {@link #getOrderNumber()} would produce for it
     */
    public static String orderNumberOf(UUID orderId) {
        return "ORD-" + orderId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public RefundStatus getRefundStatus() {
        return refundStatus;
    }

    public OffsetDateTime getRefundRetryAt() {
        return refundRetryAt;
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

    public List<OrderLine> getItems() {
        return List.copyOf(items);
    }
}
