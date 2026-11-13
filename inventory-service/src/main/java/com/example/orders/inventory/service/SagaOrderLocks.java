package com.example.orders.inventory.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-order serialization between inventory-service's saga steps.
 *
 * <p>The steps arrive on two different Kafka topics — {@code OrderCreated} on order.events,
 * {@code ReleaseStock} (and {@code CommitStock}) on inventory.commands — and Kafka gives no ordering
 * guarantee across topics. Both step handlers run one database transaction each, so a
 * transaction-scoped advisory lock keyed by the order id makes any two of them run strictly one
 * after the other, whatever order Kafka delivers the messages in:
 *
 * <ul>
 *   <li><b>Release first.</b> The release records its intent and finds no rows; the later
 *       {@code OrderCreated} sees the committed intent row and answers {@code StockRejected}
 *       instead of reserving.</li>
 *   <li><b>Reserve first.</b> Rows exist when the release's transaction runs, so the release hands
 *       the stock back, exactly as it does when the messages come in the happy order.</li>
 *   <li><b>Concurrent.</b> The second transaction blocks on the advisory lock until the first
 *       commits, and then decides on committed data. Without the lock, the intent insert and the
 *       reserve could interleave invisibly to each other (READ_COMMITTED cannot see an uncommitted
 *       insert), and a release that committed a half-second after a reserve would again leave
 *       stock in limbo.</li>
 * </ul>
 *
 * <p>The key is {@code hashtextextended(orderId-text, 0)} mapped into the 64-bit advisory-lock
 * space. Two distinct order ids colliding on the same lock value is astronomically unlikely and,
 * even then, only costs some serialization — never correctness. The lock scope is the transaction
 * ({@code pg_advisory_xact_lock}): it is released at commit/rollback and never outlives a failure.
 *
 * <p>Since each primitive takes exactly this one lock before touching any other row, the ordering
 * "advisory lock first, product rows second" is fixed and deadlocks cannot be constructed between
 * the steps.
 *
 * <p>{@code Statement.execute} is used instead of a mapped query because the advisory-lock functions
 * return {@code void}: the lock is the effect, and there is deliberately no result to map.
 */
@Component
public class SagaOrderLocks {

    private final JdbcTemplate jdbc;

    public SagaOrderLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serializes all stock steps for {@code orderId} inside the caller's transaction. The shared
     * primitives in {@link StockReservationTransactions} call this before their first read, so
     * every path — saga handler, public service, reconciliation — is covered.
     *
     * @param orderId the order whose stock-related events must not be processed concurrently
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOrder(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        jdbc.execute((java.sql.Connection connection) -> {
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, orderId.toString());
                // Result ignored on purpose: the row carries a void value, the lock is the effect.
                statement.execute();
            }
            return true;
        });
    }
}
