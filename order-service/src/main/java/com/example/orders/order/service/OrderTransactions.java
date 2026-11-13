package com.example.orders.order.service;

import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.Topics;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.support.outbox.OutboxWriter;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one write transaction of phase 5: the order, its lines and the {@code OrderCreated} outbox
 * row are committed together or not at all.
 *
 * <p>It is a separate bean because {@code @Transactional} is proxy-based — calling it from a
 * neighbouring method of {@link OrderService} would quietly bypass the proxy and run without a
 * transaction. Keeping it here also keeps the HTTP calls to inventory-service <em>outside</em> the
 * transaction: pricing happens first, so a slow catalog never holds a database connection or row
 * locks open.
 */
@Component
class OrderTransactions {

    private final OrderRepository orders;
    private final OutboxWriter outbox;

    OrderTransactions(OrderRepository orders, OutboxWriter outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    /**
     * @param userId         owner of the order
     * @param idempotencyKey replay guard, may be {@code null}
     * @param currency       ISO 4217 code every line is priced in
     * @param lines          already priced lines, at least one
     * @return the stored order, with its lines in memory and its event queued in the outbox
     */
    @Transactional
    Order placeOrder(UUID userId, String idempotencyKey, String currency, List<PricedLine> lines) {
        Order order = Order.pending(userId, currency, idempotencyKey);
        for (PricedLine line : lines) {
            order.addLine(line.productId(), line.productName(), line.unitPrice(), line.quantity());
        }
        // Flush now so a constraint violation (a name longer than the column, a duplicate
        // idempotency key inserted by a concurrent request) surfaces here, before an event is
        // queued for an order that will not exist.
        Order saved = orders.saveAndFlush(order);

        outbox.record(
                saved.getId(),
                OrderCreated.class.getSimpleName(),
                Topics.ORDER_EVENTS,
                toEvent(saved, lines));
        return saved;
    }

    private static OrderCreated toEvent(Order order, List<PricedLine> lines) {
        List<com.example.orders.common.events.OrderItem> items = lines.stream()
                .map(line -> new com.example.orders.common.events.OrderItem(
                        line.productId(), line.sku(), line.quantity(), line.unitPrice()))
                .toList();
        return new OrderCreated(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                order.getTotalAmount(),
                order.getCurrency(),
                items);
    }
}
