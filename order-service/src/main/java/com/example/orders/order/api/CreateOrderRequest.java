package com.example.orders.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code POST /api/orders}.
 *
 * <p>The owner is not in here: it is the {@code sub} claim of the bearer token. Letting a client
 * name the customer would let any account place orders for any other.
 *
 * @param items the requested lines, 1..{@value CreateOrderRequest#MAX_ITEMS}; repeated product ids
 *              are merged into one line rather than rejected
 */
public record CreateOrderRequest(

        @NotEmpty(message = "must contain at least one item")
        @Size(max = MAX_ITEMS, message = "must contain at most " + MAX_ITEMS + " items")
        @Valid
        List<OrderItemRequest> items) {

    /** Upper bound on distinct lines per order; also the number of catalog calls one POST costs. */
    public static final int MAX_ITEMS = 20;
}
