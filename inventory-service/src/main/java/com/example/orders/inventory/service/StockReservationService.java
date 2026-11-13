package com.example.orders.inventory.service;

import com.example.orders.common.events.OrderItem;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.support.error.BadRequestException;
import com.example.orders.support.tx.OptimisticRetry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Reserve, release and commit stock for an order — the piece the saga drives.
 *
 * <p>Three properties matter, and all three are tested:
 * <ul>
 *   <li><b>All or nothing.</b> Either every line of an order is reserved, or nothing is: a partial
 *       reservation would leave the saga holding stock for an order that can never ship.</li>
 *   <li><b>Idempotent.</b> Kafka redelivers, so each operation may run twice. A second
 *       {@code reserve} for the same order returns the existing outcome instead of double-booking,
 *       and a second {@code release}/{@code commit} is a no-op. The last line of defence is
 *       {@code UNIQUE(order_id, product_id)} in the database.</li>
 *   <li><b>No overselling.</b> The product rows are locked for the duration of the attempt and
 *       carry a JPA {@code @Version}; a lost race is retried a bounded number of times.</li>
 * </ul>
 *
 * <p>Phase 6 wires this to the {@code OrderCreated} / {@code PaymentCompleted} /
 * {@code PaymentFailed} consumers; phase 4 exposes it for the admin API and the tests.
 */
@Service
public class StockReservationService {

    private static final Logger log = LoggerFactory.getLogger(StockReservationService.class);

    private final StockReservationTransactions transactions;
    private final int maxAttempts;

    /**
     * @param transactions the transactional unit of work, retried as a whole
     * @param maxAttempts  attempts per operation before an optimistic-locking conflict is given up
     */
    StockReservationService(
            StockReservationTransactions transactions,
            @Value("${inventory.reservation.max-attempts:3}") int maxAttempts) {
        this.transactions = transactions;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Reserves every line of an order, or nothing at all.
     *
     * @param orderId order asking for stock
     * @param items   order lines; two lines for the same product are summed
     * @return {@code RESERVED}, {@code ALREADY_RESERVED} for a repeat call, or {@code REJECTED}
     *         with one shortage per unsatisfiable line
     * @throws BadRequestException when the request itself is unusable (no lines, non-positive
     *                            quantity, missing product id)
     */
    public ReservationResult reserve(UUID orderId, List<OrderItem> items) {
        requireOrderId(orderId);
        validate(items);
        try {
            return OptimisticRetry.execute(maxAttempts, () -> transactions.reserve(orderId, items));
        }
        catch (DataIntegrityViolationException e) {
            // Two copies of the same event raced past the pre-check: UNIQUE(order_id, product_id)
            // stopped the second one, which makes this call a no-op rather than a failure.
            log.info("Concurrent duplicate reservation for order {} was rejected by the unique index", orderId);
            return ReservationResult.alreadyReserved(orderId);
        }
    }

    /**
     * Gives the order's stock back to the shelf.
     *
     * <p>Both a {@code RESERVED} line (the order never happened) and a {@code COMMITTED} one (the
     * sale was refunded after a cancellation) end up back in {@code available}; only the column the
     * units come from differs. Lines that are already released are skipped, so a redelivered
     * {@code ReleaseStock} command is a no-op.
     *
     * @param orderId order to release
     * @return whether this call moved anything
     */
    public ReservationTransition release(UUID orderId) {
        requireOrderId(orderId);
        return OptimisticRetry.execute(maxAttempts, () -> transactions.release(orderId));
    }

    /**
     * Turns the order's holds into a finished sale: the units leave {@code reserved} and do not
     * return to {@code available}.
     *
     * @param orderId order to commit
     * @return whether this call moved anything
     */
    public ReservationTransition commit(UUID orderId) {
        requireOrderId(orderId);
        return OptimisticRetry.execute(maxAttempts, () -> transactions.commit(orderId));
    }

    /**
     * @param orderId order to inspect
     * @return its reservation rows, oldest first; empty when the order never reserved anything
     */
    public List<StockReservation> reservationsOf(UUID orderId) {
        requireOrderId(orderId);
        return transactions.findByOrder(orderId);
    }

    private static void requireOrderId(UUID orderId) {
        if (orderId == null) {
            throw new BadRequestException("orderId must be provided");
        }
    }

    private static void validate(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BadRequestException("An order must contain at least one item");
        }
        for (OrderItem item : items) {
            if (item == null || item.productId() == null) {
                throw new BadRequestException("Every order item must reference a product");
            }
            if (item.quantity() <= 0) {
                throw new BadRequestException("Quantity must be positive for product " + item.productId());
            }
        }
    }
}
