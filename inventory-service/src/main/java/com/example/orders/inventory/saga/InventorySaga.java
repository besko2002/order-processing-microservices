package com.example.orders.inventory.saga;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.support.tx.OptimisticRetry;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Entry points of inventory-service's saga steps, each a bounded retry around one transaction.
 *
 * <p>Stock rows are guarded by a JPA {@code @Version} and locked while a reservation runs, so two
 * orders touching the same product can lose an optimistic-locking race. Replaying the whole
 * transaction on fresh data is the correct answer, and it keeps the retry out of the listener — after
 * the last attempt the failure propagates, the container retries the record, and a record that keeps
 * failing is dead-lettered.
 */
@Service
public class InventorySaga {

    private final InventorySagaTransactions transactions;
    private final int maxAttempts;

    InventorySaga(
            InventorySagaTransactions transactions,
            @Value("${inventory.reservation.max-attempts:3}") int maxAttempts) {
        this.transactions = transactions;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param eventId event id, de-duplicated against {@code processed_events}
     * @param event   the new order
     */
    public void orderCreated(UUID eventId, OrderCreated event) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onOrderCreated(eventId, event);
            return null;
        });
    }

    /**
     * @param eventId event id
     * @param command the commit command
     */
    public void commitStock(UUID eventId, CommitStock command) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onCommitStock(eventId, command);
            return null;
        });
    }

    /**
     * @param eventId event id
     * @param command the release command
     */
    public void releaseStock(UUID eventId, ReleaseStock command) {
        OptimisticRetry.execute(maxAttempts, () -> {
            transactions.onReleaseStock(eventId, command);
            return null;
        });
    }
}
