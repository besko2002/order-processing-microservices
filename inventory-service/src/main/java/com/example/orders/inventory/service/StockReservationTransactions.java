package com.example.orders.inventory.service;

import com.example.orders.common.events.OrderItem;
import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ProductRepository;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.inventory.domain.StockReservationRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of stock reservation: every method here is one database transaction, so
 * {@link StockReservationService} can retry a whole attempt on a fresh transaction after a lost
 * optimistic-locking race. Keeping it in a separate bean is not decoration — a retry around a
 * self-invoked {@code @Transactional} method would replay inside the already-failed transaction.
 */
@Component
public class StockReservationTransactions {

    private static final Logger log = LoggerFactory.getLogger(StockReservationTransactions.class);

    private final ProductRepository products;
    private final StockReservationRepository reservations;

    StockReservationTransactions(ProductRepository products, StockReservationRepository reservations) {
        this.products = products;
        this.reservations = reservations;
    }

    /**
     * All-or-nothing reservation of every line of one order.
     *
     * @param orderId order asking for stock
     * @param items   the order lines; duplicates of one product are summed
     * @return what happened; on rejection no product and no reservation row was touched
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReservationResult reserve(UUID orderId, List<OrderItem> items) {
        return doReserve(orderId, items);
    }

    /**
     * The same reservation, but inside the caller's transaction.
     *
     * <p>This is what the {@code OrderCreated} consumer uses: reserving the stock and queueing the
     * {@code StockReserved} / {@code StockRejected} outbox row have to be one commit, or the saga
     * can end up with stock held for an order that was never told about it. The REST/admin path
     * keeps {@link #reserve} with its own transaction, because there it is the whole unit of work.
     *
     * @param orderId order asking for stock
     * @param items   the order lines
     * @return what happened
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationResult reserveWithin(UUID orderId, List<OrderItem> items) {
        return doReserve(orderId, items);
    }

    /**
     * @param orderId order whose holds should go back on the shelf, inside the caller's transaction
     * @return whether this call moved anything
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationTransition releaseWithin(UUID orderId) {
        return transition(orderId, ReservationStatus.RELEASED);
    }

    /**
     * @param orderId order whose holds are now a finished sale, inside the caller's transaction
     * @return whether this call moved anything
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationTransition commitWithin(UUID orderId) {
        return transition(orderId, ReservationStatus.COMMITTED);
    }

    private ReservationResult doReserve(UUID orderId, List<OrderItem> items) {
        List<StockReservation> existing = reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
        if (!existing.isEmpty()) {
            // Idempotency: this order has been decided already. Re-reserving would double-book.
            log.debug("Order {} already has {} reservation rows, nothing to do", orderId, existing.size());
            return ReservationResult.alreadyReserved(orderId);
        }

        Map<UUID, Integer> wanted = quantitiesByProduct(items);
        Map<UUID, Product> locked = products.lockAllByIdIn(wanted.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        // Check every line first: a rejection must leave the whole order untouched, so nothing is
        // written until all lines are known to be satisfiable.
        List<ReservationResult.Shortage> shortages = new ArrayList<>();
        for (Map.Entry<UUID, Integer> line : wanted.entrySet()) {
            Product product = locked.get(line.getKey());
            if (product == null) {
                shortages.add(new ReservationResult.Shortage(
                        line.getKey(), null, ReservationResult.Reason.PRODUCT_NOT_FOUND, line.getValue(), 0));
            }
            else if (product.getAvailableQuantity() < line.getValue()) {
                shortages.add(new ReservationResult.Shortage(
                        product.getId(), product.getSku(), ReservationResult.Reason.INSUFFICIENT_STOCK,
                        line.getValue(), product.getAvailableQuantity()));
            }
        }
        if (!shortages.isEmpty()) {
            log.info("Rejecting reservation for order {}: {}", orderId, shortages);
            return ReservationResult.rejected(orderId, shortages);
        }

        for (Map.Entry<UUID, Integer> line : wanted.entrySet()) {
            Product product = locked.get(line.getKey());
            if (!product.reserve(line.getValue())) {
                // Unreachable: the check above ran under the same row locks. Fail loudly rather
                // than silently reserve less than the order asked for.
                throw new IllegalStateException(
                        "Stock vanished between check and reserve for product " + product.getId());
            }
            reservations.save(StockReservation.reserve(orderId, product.getId(), line.getValue()));
        }
        // Surface a UNIQUE(order_id, product_id) violation here, inside this attempt.
        reservations.flush();
        products.flush();
        log.info("Reserved {} line(s) for order {}", wanted.size(), orderId);
        return ReservationResult.reserved(orderId);
    }

    /**
     * @param orderId order whose holds should go back on the shelf
     * @return whether this call moved anything
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReservationTransition release(UUID orderId) {
        return transition(orderId, ReservationStatus.RELEASED);
    }

    /**
     * @param orderId order whose holds are now a finished sale
     * @return whether this call moved anything
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReservationTransition commit(UUID orderId) {
        return transition(orderId, ReservationStatus.COMMITTED);
    }

    /**
     * @param orderId order to inspect
     * @return its reservation rows, oldest first
     */
    @Transactional(readOnly = true)
    List<StockReservation> findByOrder(UUID orderId) {
        return reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    /**
     * Moves an order's lines to {@code RELEASED} or {@code COMMITTED}.
     *
     * <p>Releasing accepts two starting points, and the difference matters for the money:
     * <ul>
     *   <li>a {@code RESERVED} line goes back from {@code reserved} to {@code available} — the order
     *       never happened;</li>
     *   <li>a {@code COMMITTED} line was already sold, so its units are in neither column: a release
     *       (a refund) puts them back into {@code available}.</li>
     * </ul>
     * Committing only ever accepts {@code RESERVED} lines — a sale cannot be made twice.
     *
     * <p>Lines that are already in the target state are skipped, which is what makes both operations
     * idempotent under redelivery.
     */
    private ReservationTransition transition(UUID orderId, ReservationStatus target) {
        List<StockReservation> all = reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
        if (all.isEmpty()) {
            return ReservationTransition.UNKNOWN_ORDER;
        }
        List<StockReservation> movable = all.stream()
                .filter(reservation -> canMove(reservation.getStatus(), target))
                .toList();
        if (movable.isEmpty()) {
            // Redelivery, or a release after a release: nothing left to move.
            return ReservationTransition.ALREADY_APPLIED;
        }

        Map<UUID, Product> locked = products.lockAllByIdIn(
                        movable.stream().map(StockReservation::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (StockReservation reservation : movable) {
            Product product = locked.get(reservation.getProductId());
            if (product == null) {
                throw new IllegalStateException(
                        "Reservation " + reservation.getId() + " points at a missing product");
            }
            if (target == ReservationStatus.RELEASED) {
                if (reservation.getStatus() == ReservationStatus.COMMITTED) {
                    product.returnCommitted(reservation.getQuantity());
                }
                else {
                    product.releaseReserved(reservation.getQuantity());
                }
                reservation.release();
            }
            else {
                product.commitReserved(reservation.getQuantity());
                reservation.commit();
            }
        }
        reservations.flush();
        products.flush();
        log.info("Moved {} reservation line(s) of order {} to {}", movable.size(), orderId, target);
        return ReservationTransition.APPLIED;
    }

    /**
     * @param current status of the line
     * @param target  status the caller wants
     * @return whether this line still has to move; a committed line may be released (a refund), a
     *         released line is never revived, and nothing is committed twice
     */
    private static boolean canMove(ReservationStatus current, ReservationStatus target) {
        if (target == ReservationStatus.RELEASED) {
            return current == ReservationStatus.RESERVED || current == ReservationStatus.COMMITTED;
        }
        return current == ReservationStatus.RESERVED;
    }

    /**
     * @param items order lines
     * @return quantity per product in a stable order; two lines for the same product are summed,
     *         because {@code UNIQUE(order_id, product_id)} allows only one row per product
     */
    private static Map<UUID, Integer> quantitiesByProduct(List<OrderItem> items) {
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        for (OrderItem item : items) {
            wanted.merge(item.productId(), item.quantity(), Integer::sum);
        }
        return wanted;
    }
}
