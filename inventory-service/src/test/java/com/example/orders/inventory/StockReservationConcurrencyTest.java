package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.common.events.OrderItem;
import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.service.ReservationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The oversell test: 20 orders race for 10 units.
 *
 * <p>This is the reason the reservation path locks the product rows and carries a {@code @Version}.
 * Exactly ten callers may win, available stock must end at exactly zero, and it must never be
 * observed negative in between.
 */
class StockReservationConcurrencyTest extends InventoryIntegrationTest {

    private static final int STOCK = 10;
    private static final int THREADS = 20;

    @Test
    void twentyConcurrentOrdersCannotOversellTenUnits() throws Exception {
        Product product = givenProduct("SKU-RACE", STOCK);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<ReservationResult>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    // Every thread is a different order, so idempotency cannot be what limits them.
                    return reservationService.reserve(
                            UUID.randomUUID(),
                            List.of(new OrderItem(product.getId(), product.getSku(), 1, product.getPrice())));
                }));
            }
            startLine.countDown();

            int reserved = 0;
            int rejected = 0;
            for (Future<ReservationResult> future : futures) {
                ReservationResult result = future.get(60, TimeUnit.SECONDS);
                switch (result.outcome()) {
                    case RESERVED -> reserved++;
                    case REJECTED -> {
                        rejected++;
                        assertThat(result.shortages()).singleElement().satisfies(shortage ->
                                assertThat(shortage.reason())
                                        .isEqualTo(ReservationResult.Reason.INSUFFICIENT_STOCK));
                    }
                    case ALREADY_RESERVED -> throw new AssertionError(
                            "Distinct orders must never be treated as duplicates");
                }
            }

            assertThat(reserved).isEqualTo(STOCK);
            assertThat(rejected).isEqualTo(THREADS - STOCK);
        }

        Product reloaded = products.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getAvailableQuantity()).isZero();
        assertThat(reloaded.getReservedQuantity()).isEqualTo(STOCK);
        assertThat(reservations.count()).isEqualTo(STOCK);
        assertThat(reservations.findAll())
                .allMatch(reservation -> reservation.getStatus() == ReservationStatus.RESERVED)
                .allMatch(reservation -> reservation.getQuantity() == 1);
    }

    @Test
    void concurrentDuplicatesOfTheSameOrderReserveOnlyOnce() throws Exception {
        Product product = givenProduct("SKU-DUPLICATE-EVENT", 10);
        UUID orderId = UUID.randomUUID();
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<ReservationResult>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    // Eight redeliveries of one OrderCreated event.
                    return reservationService.reserve(
                            orderId,
                            List.of(new OrderItem(product.getId(), product.getSku(), 2, product.getPrice())));
                }));
            }
            startLine.countDown();

            int reserved = 0;
            for (Future<ReservationResult> future : futures) {
                ReservationResult result = future.get(60, TimeUnit.SECONDS);
                assertThat(result.isHoldingStock()).isTrue();
                if (result.outcome() == ReservationResult.Outcome.RESERVED) {
                    reserved++;
                }
            }
            assertThat(reserved).isEqualTo(1);
        }

        Product reloaded = products.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getAvailableQuantity()).isEqualTo(8);
        assertThat(reloaded.getReservedQuantity()).isEqualTo(2);
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).hasSize(1);
    }
}
