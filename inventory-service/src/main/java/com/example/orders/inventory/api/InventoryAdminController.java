package com.example.orders.inventory.api;

import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.inventory.service.ProductService;
import com.example.orders.inventory.service.ReservationReconciliation;
import com.example.orders.inventory.service.StockReservationService;
import com.example.orders.support.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock administration. Everything under {@code /api/inventory/**} requires the ADMIN role — the
 * gateway checks that, and so does this service's own filter chain, so the rule still holds if
 * somebody reaches port 8083 directly.
 */
@RestController
@RequestMapping("/api/inventory")
class InventoryAdminController {

    private static final Logger log = LoggerFactory.getLogger(InventoryAdminController.class);

    private final ProductService products;
    private final StockReservationService reservations;
    private final ReservationReconciliation reconciliation;

    InventoryAdminController(
            ProductService products,
            StockReservationService reservations,
            ReservationReconciliation reconciliation) {
        this.products = products;
        this.reservations = reservations;
        this.reconciliation = reconciliation;
    }

    /**
     * @param request new product
     * @return {@code 201} with a {@code Location} pointing at the public catalog entry
     */
    @PostMapping("/products")
    ResponseEntity<ProductAdminResponse> create(@Valid @RequestBody CreateProductRequest request) {
        Product product = products.create(
                request.sku(),
                request.name(),
                request.description(),
                request.price(),
                request.availableQuantity());
        log.info("Admin {} created product {}", CurrentUser.id().orElse(null), product.getSku());
        return ResponseEntity
                .created(URI.create("/api/products/" + product.getId()))
                .body(ProductAdminResponse.of(product));
    }

    /**
     * @param id      product to update
     * @param request new name, description and price
     * @return the updated product
     */
    @PutMapping("/products/{id}")
    ProductAdminResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return ProductAdminResponse.of(
                products.update(id, request.name(), request.description(), request.price()));
    }

    /**
     * Restocks or corrects available stock.
     *
     * @param id      product to adjust
     * @param request the relative change
     * @return the updated product; {@code 409} when the result would be negative
     */
    @PatchMapping("/products/{id}/stock")
    ProductAdminResponse adjustStock(@PathVariable UUID id, @Valid @RequestBody StockAdjustmentRequest request) {
        Product product = products.adjustStock(id, request.delta());
        log.info("Admin {} adjusted stock of {} by {}",
                CurrentUser.id().orElse(null), product.getSku(), request.delta());
        return ProductAdminResponse.of(product);
    }

    /**
     * @param id product to delete
     * @return {@code 204}; {@code 409} while an order is holding stock of it
     */
    @DeleteMapping("/products/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        products.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * @param orderId order to inspect
     * @return the order's reservation lines, oldest first; an empty list when it never reserved
     */
    @GetMapping("/reservations")
    List<ReservationResponse> reservationsOfOrder(@RequestParam UUID orderId) {
        List<StockReservation> found = reservations.reservationsOf(orderId);
        Map<UUID, String> skus = products.skusOf(
                found.stream().map(StockReservation::getProductId).distinct().toList());
        return found.stream()
                .map(reservation -> ReservationResponse.of(reservation, skus.get(reservation.getProductId())))
                .toList();
    }

    /**
     * Recovery for reservations that leaked before the release-intent fix: cancelled orders whose
     * stock was reserved anyway and never given back. ADMIN only. The ids are candidates, not
     * proof: for each one the server asks order-service (with this administrator's own token,
     * forwarded and never logged) and releases only orders that service reports as
     * {@code CANCELLED} or {@code REJECTED}. Active, unknown, unverifiable or unverified orders are
     * refused with a per-order outcome and no effect. Every verified order is processed in its own
     * transaction serialized with running saga steps; only holding stock is touched, committed
     * (sold) rows are refused so a wrong id cannot turn a real sale into merchandise.
     *
     * @param request the orders to recover, plus an optional audit reason
     * @param jwt     the validated token of the calling administrator
     * @return one entry per distinct order id with what happened to it
     */
    @PostMapping("/reservations/reconcile")
    ReconciliationResponse reconcileReservations(
            @Valid @RequestBody ReconciliationRequest request, @AuthenticationPrincipal Jwt jwt) {
        log.info("Admin {} runs a reservation reconciliation for {} order(s): {}",
                CurrentUser.id().orElse(null), request.distinctOrderIds().size(), request.reason());
        return new ReconciliationResponse(
                reconciliation.recover(
                                request.distinctOrderIds(), request.reason(), jwt == null ? null : jwt.getTokenValue())
                        .stream()
                        .map(line -> new ReconciliationResponse.Entry(line.orderId(), line.outcome()))
                        .toList());
    }
}
