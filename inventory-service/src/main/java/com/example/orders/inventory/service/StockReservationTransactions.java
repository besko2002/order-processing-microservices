package com.example.orders.inventory.service;

import com.example.orders.common.events.OrderItem;
import com.example.orders.inventory.domain.OrderReleaseIntentRepository;
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
 *
 * <p><b>Per-order serialization and cancellation live here, in the shared primitives</b> — not in
 * the Kafka handlers that call them. Every reserve, release, commit and reconciliation first takes
 * the order's advisory lock ({@link SagaOrderLocks}) and only then reads reservation rows, so no
 * caller (a saga handler, a direct {@link StockReservationService} call, the operator tool) can
 * decide on rows another transaction is about to change. Every release records the order's
 * durable {@code order_release_intents} row in the same commit, and every reserve refuses an order
 * that has one — whichever path asked.
 */
@Component
public class StockReservationTransactions {

    private static final Logger log = LoggerFactory.getLogger(StockReservationTransactions.class);

    /** Reason stored with the release intent when a release does not name one. */
    static final String DEFAULT_RELEASE_REASON = "Stock released";

    /** Column length of {@code order_release_intents.reason}. */
    private static final int MAX_REASON_LENGTH = 500;

    private final ProductRepository products;
    private final StockReservationRepository reservations;
    private final OrderReleaseIntentRepository releaseIntents;
    private final SagaOrderLocks sagaOrderLocks;

    StockReservationTransactions(
            ProductRepository products,
            StockReservationRepository reservations,
            OrderReleaseIntentRepository releaseIntents,
            SagaOrderLocks sagaOrderLocks) {
        this.products = products;
        this.reservations = reservations;
        this.releaseIntents = releaseIntents;
        this.sagaOrderLocks = sagaOrderLocks;
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
     * Gives the order's stock back and records its release intent, inside the caller's transaction.
     * The intent is written for every outcome — released now, released before, or never reserved
     * at all — so a later {@code OrderCreated} for this order can never hold stock.
     *
     * @param orderId order whose holds should go back on the shelf
     * @param reason  what the release said; stored with the intent (first release wins), blank or
     *                {@code null} falls back to a default, long text is cut to the column length
     * @return whether this call moved anything
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationTransition releaseWithin(UUID orderId, String reason) {
        return releaseAndRecordIntent(orderId, reason);
    }

    /**
     * @param orderId order whose holds are now a finished sale, inside the caller's transaction
     * @return whether this call moved anything
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationTransition commitWithin(UUID orderId) {
        return transition(orderId, ReservationStatus.COMMITTED);
    }

    /**
     * @param orderId order whose holds should go back on the shelf
     * @return whether this call moved anything
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReservationTransition release(UUID orderId) {
        return releaseAndRecordIntent(orderId, null);
    }

    /**
     * @param orderId order whose holds are now a finished sale
     * @return whether this call moved anything
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReservationTransition commit(UUID orderId) {
        return transition(orderId, ReservationStatus.COMMITTED);
    }

    private ReservationResult doReserve(UUID orderId, List<OrderItem> items) {
        // Lock first, read second: everything below decides on rows no other step of this order
        // can change until this transaction ends.
        sagaOrderLocks.lockOrder(orderId);
        if (releaseIntents.existsById(orderId)) {
            // Cancelled (or released, or reconciled) before — possibly before this reservation was
            // even asked for, which is why "no reservation rows" can never be enough. Checked
            // ahead of the idempotency shortcut below on purpose: for a released order the rows
            // that exist are RELEASED ones, and "already reserved" would announce a hold that was
            // given back. Rejections are idempotent: replays keep landing here.
            log.info("Order {} has a release intent, refusing to reserve stock for it", orderId);
            return ReservationResult.alreadyReleased(orderId);
        }
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
     * @param orderId order to inspect
     * @return its reservation rows, oldest first
     */
    @Transactional(readOnly = true)
    List<StockReservation> findByOrder(UUID orderId) {
        return reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    /**
     * One order of the operator's reconciliation run, in its own transaction: a mistake on one
     * order must not roll back the others, and the release-intent row it writes must not depend on
     * the rest of the list either. The per-order advisory lock ({@link SagaOrderLocks}) is taken
     * inside, so this cannot interleave with a running reserve/release/commit for the same order.
     *
     * <p>Conservative by design: only {@code RESERVED} rows are handed back. Rows that were already
     * released change nothing, an order without rows only gets its intent recorded, and an order
     * with {@code COMMITTED} rows is refused outright — committed stock is a sale, and a typo'd
     * id in the operator's list must not turn money into merchandise (the refund flow does that,
     * with its own {@code ReleaseStock} command and its own bookkeeping). The intent is recorded
     * for everything this run accepted, so a duplicate {@code OrderCreated} for a recovered order
     * can never hold stock again either.
     *
     * <p><b>Package-private on purpose, and no authority on its own:</b> this method trusts that the
     * order is already known to be {@code CANCELLED} or {@code REJECTED}. The only caller,
     * {@link ReservationReconciliation}, establishes that from order-service's own answer before
     * calling; nothing outside this package can reach it.
     *
     * @param orderId order whose leaked holds are being recovered
     * @param reason  operator-supplied why, stored with the intent
     * @return what this order's rows were in and what was done about them
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReconciliationOutcome reconcileRelease(UUID orderId, String reason) {
        // Same per-order serialization the saga steps use, so a recovery can run while live
        // traffic reserves, releases or commits this very order and both see consistent rows.
        sagaOrderLocks.lockOrder(orderId);
        List<StockReservation> all = reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
        if (all.isEmpty()) {
            // Nothing here — but the operator asserts this order is finished, so the intent still
            // has to exist: stock this order would hold must never be held in the future either.
            releaseIntents.insertIfAbsent(orderId, reason);
            log.info("Reconciliation for order {}: no reservations, intent recorded", orderId);
            return ReconciliationOutcome.NO_RESERVATIONS;
        }
        if (all.stream().anyMatch(reservation -> reservation.getStatus() == ReservationStatus.COMMITTED)) {
            log.warn("Reconciliation for order {}: contains COMMITTED (sold) rows, skipping entirely",
                    orderId);
            return ReconciliationOutcome.SKIPPED_COMMITTED;
        }
        // Intent first, rows second: both commit together, and the ON CONFLICT upsert keeps the
        // first reason for repeated recovery runs.
        releaseIntents.insertIfAbsent(orderId, reason);
        List<StockReservation> held = all.stream()
                .filter(reservation -> reservation.getStatus() == ReservationStatus.RESERVED)
                .toList();
        if (held.isEmpty()) {
            log.info("Reconciliation for order {}: everything already released", orderId);
            return ReconciliationOutcome.ALREADY_RELEASED;
        }
        Map<UUID, Product> locked = products.lockAllByIdIn(
                        held.stream().map(StockReservation::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (StockReservation reservation : held) {
            Product product = locked.get(reservation.getProductId());
            if (product == null) {
                throw new IllegalStateException(
                        "Reservation " + reservation.getId() + " points at a missing product");
            }
            // Movement before the status flip, so the units are taken from the column they are in.
            product.releaseReserved(reservation.getQuantity());
            reservation.release();
        }
        reservations.flush();
        products.flush();
        log.info("Reconciliation for order {}: released {} leaked reservation row(s)",
                orderId, held.size());
        return ReconciliationOutcome.RELEASED;
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
        // Lock before the first read: rows loaded ahead of a competing release/commit would be
        // stale once the product rows are finally locked, and the same units would move twice.
        sagaOrderLocks.lockOrder(orderId);
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
                // Movement before the status flip: the units' source column is the reservation's
                // still-current status. A committed line returns its units (a refund), a reserved
                // one gives its hold back.
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
     * A release is the saga's "this order is over": rows go back to the shelf and, whatever state
     * they were in (moved now, already released, none at all), the intent is queued in the same
     * commit. The {@code ON CONFLICT} upsert keeps the first reason, so repeat releases change
     * nothing.
     */
    private ReservationTransition releaseAndRecordIntent(UUID orderId, String reason) {
        ReservationTransition transition = transition(orderId, ReservationStatus.RELEASED);
        releaseIntents.insertIfAbsent(orderId, releaseReason(reason));
        return transition;
    }

    private static String releaseReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_RELEASE_REASON;
        }
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
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
