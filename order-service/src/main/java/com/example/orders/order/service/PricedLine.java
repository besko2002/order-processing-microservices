package com.example.orders.order.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A requested line after the catalog has been asked what it costs — the price snapshot that is
 * about to be frozen onto the order.
 *
 * @param productId   catalog id
 * @param sku         business key, carried into the {@code OrderCreated} event
 * @param productName name at the moment of ordering
 * @param unitPrice   price at the moment of ordering
 * @param quantity    units ordered
 */
record PricedLine(
        UUID productId,
        String sku,
        String productName,
        BigDecimal unitPrice,
        int quantity) {
}
