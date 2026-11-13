package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.OrderItem;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.inventory.domain.OrderReleaseIntentRepository;
import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.inventory.saga.InventorySagaTransactions;
import com.example.orders.inventory.service.ReservationResult;
import com.example.orders.inventory.service.ReservationTransition;
import com.example.orders.support.outbox.OutboxRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 2: the saga's per-order serialization and its release intents have to hold at the
 * <em>shared transactional primitives</em> — {@code StockReservationService.reserve/release/commit}
 * — and not only inside the Kafka handlers that happen to take the lock first.
 *
 * <p>Nothing in production calls the public service methods today, but they are public API of a
 * Spring bean, and before this fix they ignored a persisted release intent, wrote none, and ran
 * without the per-order advisory lock: a direct call could stock a cancelled order, or race a
 * {@code commit} against a {@code release} on stale reservation rows and move the same units out
 * of {@code reserved} twice.
 *
 * <p>The races here are forced, not hoped for. A side connection holds the product row lock, so
 * every call parks at its first contended statement; the test polls {@code pg_stat_activity}
 * until the expected number of backends is really waiting, and only then lets them go. No sleeps
 * decide anything, and each assertion reads final database state — reservation rows, product
 * counters, intents and the outbox.
 */
class StockReservationPrimitiveGuardsTest extends InventoryIntegrationTest {

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private OrderReleaseIntentRepository releaseIntents;

    @Autowired
    private InventorySagaTransactions inventorySaga;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    // No table clean-up of its own: every test uses fresh random order ids and reads only its own
    // rows, so nothing here can race the live outbox relay or the Kafka listeners of this context
    // (the base class resets products and reservations before each test).

    // ------------------------------------------------------------------ intents at the primitives

    @Test
    void aReleaseThroughThePublicServiceLeavesAnIntentThatBlocksALaterOrderCreated() {
        Product product = givenProduct("SKU-PRIM-1", 10);
        UUID orderId = UUID.randomUUID();

        assertThat(reservationService.release(orderId)).isEqualTo(ReservationTransition.UNKNOWN_ORDER);
        assertThat(releaseIntents.existsById(orderId)).isTrue();

        inventorySaga.onOrderCreated(UUID.randomUUID(), orderCreatedOf(orderId, product, 3));

        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).isEmpty();
        assertThat(outboxTypes(orderId)).containsExactly("StockRejected");
        assertStock(product, 10, 0);
    }

    @Test
    void aDirectReserveAfterAPersistedSagaReleaseIsRefusedAndHoldsNothing() {
        Product product = givenProduct("SKU-PRIM-2", 10);
        UUID orderId = UUID.randomUUID();
        inventorySaga.onReleaseStock(UUID.randomUUID(), new ReleaseStock(orderId, null, "cancelled", List.of()));
        assertThat(releaseIntents.existsById(orderId)).isTrue();

        ReservationResult result = reservationService.reserve(orderId, List.of(lineOf(product, 3)));

        assertThat(result.isHoldingStock()).isFalse();
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).isEmpty();
        assertStock(product, 10, 0);
    }

    @Test
    void aReserveRepeatedAfterTheReleaseDoesNotClaimAHoldThatNoLongerExists() {
        Product product = givenProduct("SKU-PRIM-3", 10);
        UUID orderId = UUID.randomUUID();
        assertThat(reservationService.reserve(orderId, List.of(lineOf(product, 4))).isHoldingStock()).isTrue();
        assertThat(reservationService.release(orderId)).isEqualTo(ReservationTransition.APPLIED);

        // The order is cancelled: answering "already reserved" would let a caller announce a hold
        // that the release already gave back.
        ReservationResult again = reservationService.reserve(orderId, List.of(lineOf(product, 4)));

        assertThat(again.isHoldingStock()).isFalse();
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
        assertStock(product, 10, 0);
    }

    @Test
    void aCommitThatArrivesAfterTheReleaseChangesNothingOnEitherPath() {
        Product product = givenProduct("SKU-PRIM-4", 10);
        UUID viaSaga = UUID.randomUUID();
        UUID viaService = UUID.randomUUID();
        reservationService.reserve(viaSaga, List.of(lineOf(product, 2)));
        reservationService.reserve(viaService, List.of(lineOf(product, 3)));
        inventorySaga.onReleaseStock(UUID.randomUUID(), new ReleaseStock(viaSaga, null, "cancelled", List.of()));
        reservationService.release(viaService);
        assertStock(product, 10, 0);

        inventorySaga.onCommitStock(UUID.randomUUID(), new CommitStock(viaSaga));
        assertThat(reservationService.commit(viaService)).isEqualTo(ReservationTransition.ALREADY_APPLIED);

        for (UUID orderId : List.of(viaSaga, viaService)) {
            assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                    .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
        }
        assertStock(product, 10, 0);
    }

    // ------------------------------------------------------------------ forced races

    @Test
    void aCommitAndAReleaseOfOneOrderRacingThroughThePublicServiceMoveTheUnitsOnlyOnce() throws Exception {
        Product product = givenProduct("SKU-PRIM-5", 10);
        UUID raced = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        reservationService.reserve(raced, List.of(lineOf(product, 3)));
        reservationService.reserve(bystander, List.of(lineOf(product, 4)));
        assertStock(product, 3, 7);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (ProductRowBlocker blocker = new ProductRowBlocker(product.getId())) {
            Future<ReservationTransition> commit = pool.submit(() -> reservationService.commit(raced));
            Future<ReservationTransition> release = pool.submit(() -> reservationService.release(raced));
            // Both calls are now parked behind the held product row (and, once the per-order lock
            // exists, one of them behind the other's advisory lock).
            awaitBlockedBackends(2);
            blocker.close();
            commit.get(60, TimeUnit.SECONDS);
            release.get(60, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        // Whichever call went first, the units moved once: the raced order ends up released, the
        // bystander still holds exactly its own four units, and nothing is counted twice.
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(raced))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(bystander))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RESERVED));
        assertStock(product, 6, 4);
        assertConserved(product, 10);
    }

    @Test
    void aDirectReserveRacingASagaReleaseCannotLeaveAHoldBehindTheIntent() throws Exception {
        Product product = givenProduct("SKU-PRIM-6", 10);
        UUID orderId = UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (ProductRowBlocker blocker = new ProductRowBlocker(product.getId())) {
            Future<ReservationResult> reserve = pool.submit(
                    () -> reservationService.reserve(orderId, List.of(lineOf(product, 3))));
            // The reserve has read the (still empty) order and is parked on the product row.
            awaitBlockedBackends(1);
            Future<Boolean> release = pool.submit(() -> {
                inventorySaga.onReleaseStock(UUID.randomUUID(),
                        new ReleaseStock(orderId, null, "cancelled in flight", List.of()));
                return true;
            });
            // Without a shared per-order lock the release simply finishes (nothing to release, it
            // records the intent); with one it queues behind the reserve. Both are legitimate
            // states to stop waiting in — what must hold afterwards is the same.
            awaitUntil(() -> release.isDone() || blockedBackends() >= 2);
            blocker.close();
            reserve.get(60, TimeUnit.SECONDS);
            release.get(60, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(releaseIntents.findById(orderId).orElseThrow().getReason()).isEqualTo("cancelled in flight");
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                .as("a cancelled order must not keep holding stock")
                .noneMatch(row -> row.getStatus() == ReservationStatus.RESERVED);
        assertStock(product, 10, 0);
    }

    @Test
    void overlappingMultiProductOrdersInOppositeLineOrderNeverDeadlockOrOversell() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                Product first = givenProduct("SKU-PRIM-LOCK-A-" + round, 5);
                Product second = givenProduct("SKU-PRIM-LOCK-B-" + round, 5);
                CountDownLatch start = new CountDownLatch(1);
                Callable<ReservationResult> forward = () -> {
                    start.await();
                    return reservationService.reserve(UUID.randomUUID(),
                            List.of(lineOf(first, 3), lineOf(second, 3)));
                };
                Callable<ReservationResult> backward = () -> {
                    start.await();
                    return reservationService.reserve(UUID.randomUUID(),
                            List.of(lineOf(second, 2), lineOf(first, 2)));
                };
                Future<ReservationResult> a = pool.submit(forward);
                Future<ReservationResult> b = pool.submit(backward);
                start.countDown();

                // 3 + 2 fits into 5: both orders must succeed, in whatever order they are served.
                assertThat(a.get(60, TimeUnit.SECONDS).isHoldingStock()).isTrue();
                assertThat(b.get(60, TimeUnit.SECONDS).isHoldingStock()).isTrue();
                assertStock(first, 0, 5);
                assertStock(second, 0, 5);
            }
        }
        finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void assertStock(Product product, int available, int reserved) {
        Product reloaded = products.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getAvailableQuantity()).as("available of %s", product.getSku()).isEqualTo(available);
        assertThat(reloaded.getReservedQuantity()).as("reserved of %s", product.getSku()).isEqualTo(reserved);
    }

    /** Units on the shelf + units held + units sold always add up to what the warehouse had. */
    private void assertConserved(Product product, int warehouse) {
        Product reloaded = products.findById(product.getId()).orElseThrow();
        int sold = reservations.findAll().stream()
                .filter(row -> row.getProductId().equals(product.getId()))
                .filter(row -> row.getStatus() == ReservationStatus.COMMITTED)
                .mapToInt(StockReservation::getQuantity)
                .sum();
        assertThat(reloaded.getAvailableQuantity() + reloaded.getReservedQuantity() + sold).isEqualTo(warehouse);
        assertThat(reloaded.getReservedQuantity()).isEqualTo(reservations.findAll().stream()
                .filter(row -> row.getProductId().equals(product.getId()))
                .filter(row -> row.getStatus() == ReservationStatus.RESERVED)
                .mapToInt(StockReservation::getQuantity)
                .sum());
    }

    private static OrderItem lineOf(Product product, int quantity) {
        return new OrderItem(product.getId(), product.getSku(), quantity, product.getPrice());
    }

    private static OrderCreated orderCreatedOf(UUID orderId, Product product, int quantity) {
        return new OrderCreated(orderId, "ORD-PRIM", UUID.randomUUID(),
                product.getPrice().multiply(BigDecimal.valueOf(quantity)), "EUR",
                List.of(lineOf(product, quantity)));
    }

    private List<String> outboxTypes(UUID orderId) {
        List<String> types = new ArrayList<>();
        outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).forEach(row -> types.add(row.getEventType()));
        return types;
    }

    /** @return backends of this database that are currently waiting for a lock (row or advisory) */
    private int blockedBackends() {
        Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock'
                """, Integer.class);
        return waiting == null ? 0 : waiting;
    }

    private void awaitBlockedBackends(int expected) {
        awaitUntil(() -> blockedBackends() >= expected);
    }

    /** Polls a condition that a database state change makes true; fails loudly instead of hanging. */
    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting", e);
            }
        }
        throw new AssertionError("The expected database state was not reached within 30 seconds");
    }

    /** Holds the row lock of one product from a side connection until {@link #close()}. */
    private final class ProductRowBlocker implements AutoCloseable {

        private final Connection connection;
        private boolean open = true;

        ProductRowBlocker(UUID productId) throws SQLException {
            this.connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT id FROM products WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, productId);
                statement.execute();
            }
        }

        @Override
        public void close() throws SQLException {
            if (open) {
                open = false;
                try {
                    connection.commit();
                }
                finally {
                    connection.close();
                }
            }
        }
    }
}
