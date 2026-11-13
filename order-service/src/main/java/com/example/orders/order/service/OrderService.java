package com.example.orders.order.service;

import com.example.orders.order.api.CreateOrderRequest;
import com.example.orders.order.api.OrderEventResponse;
import com.example.orders.order.api.OrderItemRequest;
import com.example.orders.order.api.OrderResponse;
import com.example.orders.order.client.CatalogProduct;
import com.example.orders.order.client.ProductCatalogClient;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderLine;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.saga.OrderSaga;
import com.example.orders.support.error.BadRequestException;
import com.example.orders.support.error.ConflictException;
import com.example.orders.support.error.NotFoundException;
import com.example.orders.support.outbox.OutboxRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Placing and reading orders.
 *
 * <p>Placing one runs in three steps that are deliberately not in one transaction: validate and
 * normalise the request, ask inventory-service what the products cost, then commit the order, its
 * lines and its {@code OrderCreated} outbox row in a single local transaction
 * ({@link OrderTransactions}). Network calls stay outside that transaction on purpose — a catalog
 * that takes two seconds must not hold a database connection for two seconds.
 */
@Service
public class OrderService {

    /** Page size used when the caller does not ask for one. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard ceiling on the page size, so one request cannot ask for every order. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Longest {@code Idempotency-Key} accepted; matches the column. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final ProductCatalogClient catalog;
    private final OrderTransactions transactions;
    private final OrderSaga saga;
    private final OutboxRepository outbox;

    OrderService(
            OrderRepository orders,
            ProductCatalogClient catalog,
            OrderTransactions transactions,
            OrderSaga saga,
            OutboxRepository outbox) {
        this.orders = orders;
        this.catalog = catalog;
        this.transactions = transactions;
        this.saga = saga;
        this.outbox = outbox;
    }

    // ------------------------------------------------------------------ commands

    /**
     * @param userId         owner, taken from the token
     * @param request        the requested lines
     * @param idempotencyKey value of the optional {@code Idempotency-Key} header
     * @return the order and whether this call created it
     * @throws BadRequestException when the request is unusable or names a product the catalog does
     *                             not know
     * @throws ConflictException   when the key was already used for a different set of lines
     */
    public OrderPlacement place(UUID userId, CreateOrderRequest request, String idempotencyKey) {
        Map<UUID, Integer> wanted = merge(request.items());
        String key = normaliseKey(idempotencyKey);

        if (key != null) {
            Optional<Order> replayed = orders.findByUserIdAndIdempotencyKey(userId, key);
            if (replayed.isPresent()) {
                return replay(replayed.get(), wanted, key);
            }
        }

        List<PricedLine> priced = price(wanted);
        try {
            Order order = transactions.placeOrder(userId, key, Order.DEFAULT_CURRENCY, priced);
            log.info("Placed order {} for user {} with {} line(s), total {} {}",
                    order.getId(), userId, priced.size(), order.getTotalAmount(), order.getCurrency());
            return new OrderPlacement(OrderResponse.of(order), true);
        }
        catch (DataIntegrityViolationException e) {
            if (key == null) {
                throw e;
            }
            // Two requests carrying the same key raced; the unique constraint settled it. The
            // loser answers with the winner's order rather than with an error.
            Order winner = orders.findByUserIdAndIdempotencyKey(userId, key).orElseThrow(() -> e);
            return replay(winner, wanted, key);
        }
    }

    /**
     * Cancels an order on the customer's request.
     *
     * <p>Only the decision is made here; the compensating messages and the state change are one
     * transaction in the saga, because "the customer was told it is cancelled" and "the stock and the
     * money are on their way back" must not be separable.
     *
     * @param orderId  order to cancel
     * @param callerId the authenticated caller
     * @param admin    whether the caller holds {@code ADMIN}
     * @return the order as it stands after the cancellation was started
     * @throws NotFoundException when the order does not exist or belongs to somebody else
     * @throws ConflictException when it is already rejected, cancelled, or being cancelled
     */
    public OrderResponse cancel(UUID orderId, UUID callerId, boolean admin) {
        return saga.cancel(orderId, callerId, admin);
    }

    // ------------------------------------------------------------------ queries

    /**
     * An order another user owns is reported as {@code 404}, not {@code 403}: a {@code 403} would
     * confirm that the id exists, which is exactly the information the owner is entitled to keep.
     * Administrators see every order.
     *
     * @param orderId  order to read
     * @param callerId the authenticated caller
     * @param admin    whether the caller holds {@code ADMIN}
     * @return the order
     * @throws NotFoundException when it does not exist, or belongs to somebody else
     */
    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId, UUID callerId, boolean admin) {
        return orders.findWithItemsById(orderId)
                .filter(order -> admin || order.getUserId().equals(callerId))
                .map(OrderResponse::of)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
    }

    /**
     * The events and commands order-service emitted for one order, oldest first. Same access rule
     * as {@link #get(UUID, UUID, boolean)}: owner or administrator, otherwise {@code 404}.
     *
     * @param orderId  order whose events to read
     * @param callerId the authenticated caller
     * @param admin    whether the caller holds {@code ADMIN}
     * @return one entry per outbox row written for the order; payloads are not included
     * @throws NotFoundException when it does not exist, or belongs to somebody else
     */
    @Transactional(readOnly = true)
    public List<OrderEventResponse> events(UUID orderId, UUID callerId, boolean admin) {
        get(orderId, callerId, admin);
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .map(OrderEventResponse::of)
                .toList();
    }

    /**
     * @param userId owner
     * @param page   zero-based page index; negative values are treated as 0
     * @param size   requested page size; clamped to 1..{@value #MAX_PAGE_SIZE}
     * @return that user's orders, newest first
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse> listOwn(UUID userId, int page, int size) {
        return orders.findByUserId(userId, newestFirst(page, size)).map(OrderResponse::of);
    }

    /**
     * @param status optional status filter; {@code null} lists every status
     * @param page   zero-based page index
     * @param size   requested page size; clamped to 1..{@value #MAX_PAGE_SIZE}
     * @return orders of every user, newest first
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse> listAll(OrderStatus status, int page, int size) {
        Pageable pageable = newestFirst(page, size);
        Page<Order> found = status == null
                ? orders.findAll(pageable)
                : orders.findByStatus(status, pageable);
        return found.map(OrderResponse::of);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Collapses repeated product ids into one line each, keeping the order they first appeared in.
     * Two lines of the same product are a client quirk, not an error — but they must become one
     * line, because {@code order_items} holds one row per (order, product).
     */
    private static Map<UUID, Integer> merge(List<OrderItemRequest> items) {
        Map<UUID, Integer> merged = new LinkedHashMap<>();
        for (OrderItemRequest item : items) {
            merged.merge(item.productId(), item.quantity(), Integer::sum);
        }
        for (Map.Entry<UUID, Integer> line : merged.entrySet()) {
            if (line.getValue() > OrderItemRequest.MAX_QUANTITY) {
                throw new BadRequestException(
                        "Quantity for product %s adds up to %d, the maximum is %d"
                                .formatted(line.getKey(), line.getValue(), OrderItemRequest.MAX_QUANTITY));
            }
        }
        if (merged.size() > CreateOrderRequest.MAX_ITEMS) {
            throw new BadRequestException("An order may contain at most %d distinct products"
                    .formatted(CreateOrderRequest.MAX_ITEMS));
        }
        return merged;
    }

    /** One catalog call per distinct product; an unknown id fails the whole order with a 400. */
    private List<PricedLine> price(Map<UUID, Integer> wanted) {
        List<PricedLine> priced = new ArrayList<>(wanted.size());
        for (Map.Entry<UUID, Integer> line : wanted.entrySet()) {
            CatalogProduct product = catalog.fetch(line.getKey());
            priced.add(new PricedLine(
                    line.getKey(), product.sku(), product.name(), product.price(), line.getValue()));
        }
        return priced;
    }

    private static String normaliseKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BadRequestException(
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * A replay is only a replay when the body matches. The same key with different lines means the
     * client reused a key it should not have, and answering with the old order would hide that a
     * request was never carried out — so it is a conflict.
     */
    private static OrderPlacement replay(Order existing, Map<UUID, Integer> wanted, String key) {
        Map<UUID, Integer> stored = existing.getItems().stream()
                .collect(Collectors.toMap(OrderLine::getProductId, OrderLine::getQuantity));
        if (!stored.equals(wanted)) {
            throw new ConflictException(
                    "Idempotency-Key '%s' was already used for a different order".formatted(key));
        }
        log.info("Idempotency-Key '{}' replayed order {}", key, existing.getId());
        return new OrderPlacement(OrderResponse.of(existing), false);
    }

    private static Pageable newestFirst(int page, int size) {
        return PageRequest.of(
                Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                // id breaks ties, so two orders created in the same millisecond keep a stable
                // position instead of drifting between pages.
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
