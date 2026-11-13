package com.example.orders.order.api;

import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.service.OrderPlacement;
import com.example.orders.order.service.OrderService;
import com.example.orders.support.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order API. Every endpoint here needs a token; who may see what is decided from the token's
 * {@code sub} claim and its roles, never from anything the client sends in the body or a header.
 */
@RestController
@RequestMapping("/api/orders")
class OrderController {

    /** Optional header that makes {@code POST /api/orders} safe to retry. */
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Role that may list other people's orders. */
    private static final String ADMIN = "ADMIN";

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    /**
     * Places an order for the authenticated caller.
     *
     * <p>Sending an {@code Idempotency-Key} makes the call safe to repeat: the same key with the
     * same items answers {@code 200} with the order that already exists instead of creating a
     * second one, and the same key with different items is a {@code 409}.
     *
     * @param request        the requested lines
     * @param idempotencyKey optional replay guard
     * @return {@code 201} with the new order, or {@code 200} with the replayed one
     */
    @PostMapping
    ResponseEntity<OrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        OrderPlacement placement = orders.place(CurrentUser.requireId(), request, idempotencyKey);
        URI location = URI.create("/api/orders/" + placement.order().id());
        return placement.created()
                ? ResponseEntity.created(location).body(placement.order())
                : ResponseEntity.ok().location(location).body(placement.order());
    }

    /**
     * @param page zero-based page index
     * @param size page size, clamped to at most {@value OrderService#MAX_PAGE_SIZE}
     * @return the caller's own orders, newest first
     */
    @GetMapping("/me")
    PageResponse<OrderResponse> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + OrderService.DEFAULT_PAGE_SIZE) int size) {
        return PageResponse.of(orders.listOwn(CurrentUser.requireId(), page, size));
    }

    /**
     * Every order in the system. ADMIN only — the security rules keep everybody else out before
     * this method is reached.
     *
     * @param status optional status filter
     * @param page   zero-based page index
     * @param size   page size, clamped to at most {@value OrderService#MAX_PAGE_SIZE}
     * @return one page of orders, newest first
     */
    @GetMapping
    PageResponse<OrderResponse> all(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + OrderService.DEFAULT_PAGE_SIZE) int size) {
        return PageResponse.of(orders.listAll(status, page, size));
    }

    /**
     * @param id order id
     * @return the order, if the caller owns it or is an administrator; otherwise {@code 404} —
     *         see {@link OrderService#get(UUID, UUID, boolean)} for why it is not a {@code 403}
     */
    @GetMapping("/{id}")
    OrderResponse one(@PathVariable UUID id) {
        return orders.get(id, CurrentUser.requireId(), CurrentUser.hasRole(ADMIN));
    }

    /**
     * Cancels an order the caller owns (an administrator may cancel any order).
     *
     * <p>What that means depends on how far the saga got: an order that is only holding stock is
     * cancelled at once, while a {@code CONFIRMED} order first has to get its money back, so it
     * answers {@code CANCELLATION_PENDING} and reaches {@code CANCELLED} when the refund lands.
     *
     * @param id order to cancel
     * @return the order as it stands after the cancellation was started
     */
    @PostMapping("/{id}/cancel")
    OrderResponse cancel(@PathVariable UUID id) {
        return orders.cancel(id, CurrentUser.requireId(), CurrentUser.hasRole(ADMIN));
    }
}
