package com.example.orders.order.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.orders.common.events.OrderCancelled;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.RefundPayment;
import com.example.orders.common.events.Topics;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.RefundStatus;
import com.example.orders.support.idempotency.ProcessedEvents;
import com.example.orders.support.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The reviewer's regression tests for the two refund-bookkeeping defects found in the Phase-1
 * review, kept in the repository (adapted where the fix deliberately changes the driver).
 *
 * <p>Adaptation notes, assertions untouched:
 * <ul>
 *   <li>{@code cancelledOrderWithRequestedRefundMustBeEligibleForRetry} drives the refund-recovery
 *       entry point ({@link OrderSagaTransactions#recoverOutstandingRefund}) instead of
 *       {@code timeOut}: the fix deliberately stops re-commanding refunds through the order's
 *       timeout (final states must not be re-cancelled) and moves that work to the recovery sweep's
 *       dedicated method — the same one the sweeper calls per selected order. The retry interval is
 *       satisfied generously here: the fixture's refund was stamped moments ago, and the test
 *       asserts eligibility, not timing.</li>
 *   <li>{@code cancellationPendingRefundMustStayRetryableAfterFirstTimeout} originally demanded
 *       two <em>immediate</em> {@code timeOut} re-queues. That demanded the premature retry the
 *       second review round found: {@code timeOut} runs on the order timeout's clock, the refund
 *       retries on {@code refund_retry_at}'s, and with a retry interval longer than the timeout
 *       the first would fire early. The reviewer's assertions are kept verbatim (two re-queued
 *       commands, at most one announcement, still {@code REQUESTED}, still waiting or cancelled);
 *       only the driver changed: {@code timeOut} must now leave the refund alone, and the two
 *       retries come from {@code recoverOutstandingRefund} in two controlled windows — the
 *       booked attempt is aged by hand between them instead of sleeping a retry interval.</li>
 *   <li>Everything else is verbatim the reviewer's file, which passes against the fixed code.</li>
 * </ul>
 */
class PhaseOneReviewRegressionTest {

    private static final String CURRENCY = "USD";
    private static final String OUTBOX_REFUND = RefundPayment.class.getSimpleName();
    private static final String OUTBOX_CANCELLED = OrderCancelled.class.getSimpleName();

    private OrderRepository repository;
    private OutboxWriter outbox;
    private ProcessedEvents processedEvents;
    private OrderSagaTransactions transactions;

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        outbox = mock(OutboxWriter.class);
        processedEvents = mock(ProcessedEvents.class);
        when(processedEvents.markProcessed(any(), anyString())).thenReturn(true);
        when(repository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        transactions = new OrderSagaTransactions(repository, outbox, processedEvents);
    }

    @Test
    void foreignPaymentRefundedMustNotCompleteTrackedRefund() {
        UUID trackedPayment = UUID.randomUUID();
        UUID foreignPayment = UUID.randomUUID();
        Order order = pricedOrder();
        order.markConfirmed(trackedPayment);
        assertThat(order.requestRefund()).isTrue();
        order.markCancellationPending(OrderSagaTransactions.CANCELLED_BY_CUSTOMER);
        stubFind(order);

        transactions.onPaymentRefunded(UUID.randomUUID(),
                new PaymentRefunded(order.getId(), foreignPayment, order.getTotalAmount(), CURRENCY, "foreign"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
        assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(order.getPaymentId()).isEqualTo(trackedPayment);
        verify(outbox, never()).record(any(UUID.class), eq(OUTBOX_CANCELLED), anyString(), any());
    }

    @Test
    void cancelledOrderWithRequestedRefundMustBeEligibleForRetry() {
        UUID trackedPayment = UUID.randomUUID();
        Order order = pricedOrder();
        // The "payment completed after the cancellation" case: order is final, refund is still owed.
        order.rememberPayment(trackedPayment);
        assertThat(order.requestRefund()).isTrue();
        order.markCancelled("Cancelled by the customer");
        stubFind(order);

        assertThat(transactions.recoverOutstandingRefund(
                order.getId(), "refund timeout", OffsetDateTime.now().plusSeconds(60))).isTrue();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).record(eq(order.getId()), eq(OUTBOX_REFUND), eq(Topics.PAYMENT_COMMANDS), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(RefundPayment.class);
        RefundPayment command = (RefundPayment) payload.getValue();
        assertThat(command.orderId()).isEqualTo(order.getId());
        assertThat(command.paymentId()).isEqualTo(trackedPayment);
        assertThat(command.amount()).isEqualByComparingTo(order.getTotalAmount());
        assertThat(command.currency()).isEqualTo(CURRENCY);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
        // The cancellation was announced long ago; a retry must not announce it again.
        verify(outbox, never()).record(any(UUID.class), eq(OUTBOX_CANCELLED), anyString(), any());
    }

    @Test
    void cancellationPendingRefundMustStayRetryableAfterFirstTimeout() {
        UUID trackedPayment = UUID.randomUUID();
        Order order = pricedOrder();
        order.markConfirmed(trackedPayment);
        assertThat(order.requestRefund()).isTrue();
        order.markCancellationPending(OrderSagaTransactions.CANCELLED_BY_CUSTOMER);
        stubFind(order);

        // The order timeout is not a refund clock: the order is waiting for its money, not stuck,
        // so a timeout leaves it — and its refund — exactly as they are, however often it fires.
        OffsetDateTime booked = order.getRefundRetryAt();
        assertThat(transactions.timeOut(order.getId(), "refund timeout 0")).isFalse();
        assertThat(transactions.timeOut(order.getId(), "refund timeout 0 again")).isFalse();
        verify(outbox, never()).record(any(UUID.class), eq(OUTBOX_REFUND), anyString(), any());
        assertThat(order.getRefundRetryAt()).isEqualTo(booked);

        // Two retries, each in its own eligible window: the refund was last commanded a retry
        // interval (here: 10 minutes) ago, so the recovery's due-before cutoff has passed it.
        Duration retryInterval = Duration.ofMinutes(10);
        order.bookRefundRetry(OffsetDateTime.now().minus(retryInterval).minusSeconds(5));
        assertThat(transactions.recoverOutstandingRefund(
                order.getId(), "refund timeout 1", OffsetDateTime.now().minus(retryInterval))).isTrue();
        // The attempt just booked closes the window: the same cutoff finds nothing due any more.
        assertThat(transactions.recoverOutstandingRefund(
                order.getId(), "refund immediate repeat", OffsetDateTime.now().minus(retryInterval))).isFalse();
        // One retry interval later (the booked attempt aged by hand) the second window opens.
        order.bookRefundRetry(OffsetDateTime.now().minus(retryInterval).minusSeconds(5));
        assertThat(transactions.recoverOutstandingRefund(
                order.getId(), "refund timeout 2", OffsetDateTime.now().minus(retryInterval))).isTrue();

        // Both retries must re-queue the refund: the first one must not turn the order final.
        verify(outbox, times(2)).record(
                eq(order.getId()), eq(OUTBOX_REFUND), eq(Topics.PAYMENT_COMMANDS), any(RefundPayment.class));
        // Waiting for the refund, the customer is never told "cancelled" twice (at most once if a fix
        // chooses to announce the cancellation while only the refund flag stays open).
        verify(outbox, atMost(1)).record(any(UUID.class), eq(OUTBOX_CANCELLED), anyString(), any());
        assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(List.of(OrderStatus.CANCELLATION_PENDING, OrderStatus.CANCELLED))
                .contains(order.getStatus());
    }

    private void stubFind(Order order) {
        when(repository.findById(order.getId())).thenReturn(Optional.of(order));
    }

    private static Order pricedOrder() {
        Order order = Order.pending(UUID.randomUUID(), CURRENCY, null);
        order.addLine(UUID.randomUUID(), "Widget", new BigDecimal("12.50"), 2);
        return order;
    }
}
