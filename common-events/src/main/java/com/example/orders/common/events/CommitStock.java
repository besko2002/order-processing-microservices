package com.example.orders.common.events;

import java.util.UUID;

/**
 * Completion command: the order was paid, so the units inventory-service is holding for it are now
 * sold and must leave the warehouse for good.
 *
 * <p>Only the order id is needed. inventory-service owns the reservation rows and finds them by
 * order, which keeps the command valid however many lines the order has.
 *
 * @param orderId order whose holds become a finished sale; also the Kafka message key
 */
public record CommitStock(UUID orderId) {
}
