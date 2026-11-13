package com.example.orders.inventory.saga;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.common.events.StockRejected;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.inventory.domain.StockReservationRepository;
import com.example.orders.inventory.service.ReservationResult;
import com.example.orders.inventory.service.ReservationTransition;
import com.example.orders.inventory.service.StockReservationTransactions;
import com.example.orders.support.idempotency.ProcessedEvents;
import com.example.orders.support.outbox.OutboxWriter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * inventory-service's side of the saga: one transaction per consumed message.
 *
 * <p>Each method claims the event in {@code processed_events}, changes stock, and queues its answer
 * in the outbox — all in a single commit. That is what makes the step reliable in both directions:
 * stock is never held for an order that was never told about it, and no {@code StockReserved} is ever
 * announced for stock that was not actually taken.
 *
 * <p>inventory-service reacts to {@code OrderCreated} rather than to a {@code ReserveStock} command:
 * reserving is the saga's first step and follows directly from the order existing, so an extra
 * command would only add a hop that could fail. The compensating and completing steps
 * ({@code ReleaseStock}, {@code CommitStock}) <em>are</em> commands, because they are decisions only
 * the orchestrator can make.
 */
@Component
public class InventorySagaTransactions {

    private static final Logger log = LoggerFactory.getLogger(InventorySagaTransactions.class);

    /** Consumer name recorded in {@code processed_events} for the order topic. */
    public static final String ORDER_CONSUMER = "inventory.order-events";

    /** Consumer name recorded in {@code processed_events} for the command topic. */
    public static final String COMMAND_CONSUMER = "inventory.commands";

    private final StockReservationTransactions reservations;
    private final StockReservationRepository reservationRows;
    private final OutboxWriter outbox;
    private final ProcessedEvents processedEvents;

    InventorySagaTransactions(
            StockReservationTransactions reservations,
            StockReservationRepository reservationRows,
            OutboxWriter outbox,
            ProcessedEvents processedEvents) {
        this.reservations = reservations;
        this.reservationRows = reservationRows;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
    }

    /**
     * Saga step 2: try to hold every line of a new order.
     *
     * @param eventId id of the {@code OrderCreated} event
     * @param event   its payload
     */
    @Transactional
    public void onOrderCreated(UUID eventId, OrderCreated event) {
        if (!processedEvents.markProcessed(eventId, ORDER_CONSUMER)) {
            return;
        }
        if (event.orderId() == null || event.items() == null || event.items().isEmpty()) {
            // A well-formed envelope carrying an order nothing can be done with. Rejecting is the
            // honest answer: the orchestrator needs an answer, and there is nothing to reserve.
            log.warn("OrderCreated {} has no usable items, rejecting", event.orderId());
            reject(event.orderId(), "The order contains no items", List.of());
            return;
        }

        ReservationResult result = reservations.reserveWithin(event.orderId(), event.items());
        if (result.isHoldingStock()) {
            UUID reservationId = representativeReservationId(event.orderId());
            outbox.record(event.orderId(), StockReserved.class.getSimpleName(), Topics.INVENTORY_EVENTS,
                    new StockReserved(event.orderId(), reservationId, event.items()));
            log.info("Reserved stock for order {} (reservation {}, outcome {})",
                    event.orderId(), reservationId, result.outcome());
            return;
        }
        String reason = describe(result);
        reject(event.orderId(), reason, result.shortages().stream()
                .map(shortage -> shortage.sku() == null ? String.valueOf(shortage.productId()) : shortage.sku())
                .toList());
        log.info("Rejected stock for order {}: {}", event.orderId(), reason);
    }

    /**
     * Completion command: the order was paid, the units are sold.
     *
     * @param eventId id of the {@code CommitStock} command
     * @param command its payload
     */
    @Transactional
    public void onCommitStock(UUID eventId, CommitStock command) {
        if (!processedEvents.markProcessed(eventId, COMMAND_CONSUMER)) {
            return;
        }
        if (command.orderId() == null) {
            log.warn("Ignoring CommitStock without an order id");
            return;
        }
        ReservationTransition transition = reservations.commitWithin(command.orderId());
        log.info("CommitStock for order {}: {}", command.orderId(), transition);
    }

    /**
     * Compensating command: give the order's stock back.
     *
     * @param eventId id of the {@code ReleaseStock} command
     * @param command its payload
     */
    @Transactional
    public void onReleaseStock(UUID eventId, ReleaseStock command) {
        if (!processedEvents.markProcessed(eventId, COMMAND_CONSUMER)) {
            return;
        }
        if (command.orderId() == null) {
            log.warn("Ignoring ReleaseStock without an order id");
            return;
        }
        ReservationTransition transition = reservations.releaseWithin(command.orderId());
        // UNKNOWN_ORDER is normal and not a failure: the orchestrator releases unconditionally when
        // it cancels, because it cannot know whether a reservation was ever taken.
        log.info("ReleaseStock for order {} ({}): {}",
                command.orderId(), command.reason(), transition);
    }

    private void reject(UUID orderId, String reason, List<String> unavailableSkus) {
        outbox.record(orderId, StockRejected.class.getSimpleName(), Topics.INVENTORY_EVENTS,
                new StockRejected(orderId, reason, unavailableSkus));
    }

    /**
     * @param orderId order that now holds stock
     * @return the id of the order's first reservation row. An order reserves one row per product, so
     *         there is no single row that "is" the reservation; this is the representative the
     *         {@code StockReserved} event carries, and every later command addresses the order id —
     *         inventory-service owns the rows and looks them up itself.
     */
    private UUID representativeReservationId(UUID orderId) {
        List<StockReservation> rows = reservationRows.findByOrderIdOrderByCreatedAtAsc(orderId);
        return rows.isEmpty() ? null : rows.getFirst().getId();
    }

    private static String describe(ReservationResult result) {
        if (result.shortages().isEmpty()) {
            return "Stock could not be reserved";
        }
        return result.shortages().stream()
                .map(shortage -> switch (shortage.reason()) {
                    case PRODUCT_NOT_FOUND -> "product %s is unknown".formatted(shortage.productId());
                    case INSUFFICIENT_STOCK -> "%s: requested %d, available %d"
                            .formatted(shortage.sku(), shortage.requested(), shortage.available());
                })
                .collect(Collectors.joining("; ", "Insufficient stock (", ")"));
    }
}
