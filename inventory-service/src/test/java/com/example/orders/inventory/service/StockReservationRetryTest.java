package com.example.orders.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.orders.common.events.OrderItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * How the service reacts to a lost race, without a database in the way: the transactional unit is
 * replayed a bounded number of times, and a unique-constraint violation is read as "somebody else
 * already reserved this order".
 */
class StockReservationRetryTest {

    private static final int MAX_ATTEMPTS = 3;

    private final StockReservationTransactions transactions = mock(StockReservationTransactions.class);
    private final StockReservationService service = new StockReservationService(transactions, MAX_ATTEMPTS);

    private final UUID orderId = UUID.randomUUID();
    private final List<OrderItem> items =
            List.of(new OrderItem(UUID.randomUUID(), "SKU-1", 1, new BigDecimal("1.00")));

    @Test
    void reserveRetriesUntilAnAttemptCommits() {
        when(transactions.reserve(eq(orderId), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("Product", UUID.randomUUID()))
                .thenThrow(new ObjectOptimisticLockingFailureException("Product", UUID.randomUUID()))
                .thenReturn(ReservationResult.reserved(orderId));

        ReservationResult result = service.reserve(orderId, items);

        assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.RESERVED);
        verify(transactions, times(MAX_ATTEMPTS)).reserve(eq(orderId), any());
    }

    @Test
    void reserveGivesUpAfterTheBoundedNumberOfAttempts() {
        when(transactions.reserve(eq(orderId), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("Product", UUID.randomUUID()));

        assertThatThrownBy(() -> service.reserve(orderId, items))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(transactions, times(MAX_ATTEMPTS)).reserve(eq(orderId), any());
    }

    @Test
    void aUniqueConstraintViolationIsReadAsAnAlreadyReservedOrder() {
        when(transactions.reserve(eq(orderId), any()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_stock_reservations_order_product\""));

        ReservationResult result = service.reserve(orderId, items);

        assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.ALREADY_RESERVED);
        assertThat(result.isHoldingStock()).isTrue();
    }

    @Test
    void releaseAndCommitAreRetriedTheSameWay() {
        when(transactions.release(orderId))
                .thenThrow(new ObjectOptimisticLockingFailureException("Product", UUID.randomUUID()))
                .thenReturn(ReservationTransition.APPLIED);
        when(transactions.commit(orderId))
                .thenThrow(new ObjectOptimisticLockingFailureException("Product", UUID.randomUUID()))
                .thenReturn(ReservationTransition.APPLIED);

        assertThat(service.release(orderId)).isEqualTo(ReservationTransition.APPLIED);
        assertThat(service.commit(orderId)).isEqualTo(ReservationTransition.APPLIED);

        verify(transactions, times(2)).release(orderId);
        verify(transactions, times(2)).commit(orderId);
    }
}
