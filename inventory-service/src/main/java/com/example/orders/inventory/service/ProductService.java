package com.example.orders.inventory.service;

import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ProductRepository;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.StockReservationRepository;
import com.example.orders.support.error.ConflictException;
import com.example.orders.support.error.NotFoundException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catalog reads for everybody and catalog writes for administrators. */
@Service
public class ProductService {

    /** Page size used when the caller does not ask for one. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard ceiling on the page size, so one request cannot ask for the whole catalog. */
    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository products;
    private final StockReservationRepository reservations;

    ProductService(ProductRepository products, StockReservationRepository reservations) {
        this.products = products;
        this.reservations = reservations;
    }

    /**
     * @param page zero-based page index; negative values are treated as 0
     * @param size requested page size; clamped to 1..{@value #MAX_PAGE_SIZE}
     * @param term optional case-insensitive substring matched against name and SKU
     * @return one page of the catalog, ordered by name
     */
    @Transactional(readOnly = true)
    public Page<Product> list(int page, int size, String term) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.ASC, "name"));
        if (term == null || term.isBlank()) {
            return products.findAll(pageable);
        }
        return products.search(term.trim(), pageable);
    }

    /**
     * @param id product id
     * @return the product
     * @throws NotFoundException when no product has that id
     */
    @Transactional(readOnly = true)
    public Product get(UUID id) {
        return products.findById(id).orElseThrow(() -> NotFoundException.of("Product", id));
    }

    /**
     * @param ids product ids to resolve
     * @return SKU per id; ids that no longer exist are simply absent
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> skusOf(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return products.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Product::getSku));
    }

    /**
     * @param sku               business key, must not be taken yet
     * @param name              display name
     * @param description       optional description
     * @param price             unit price
     * @param availableQuantity starting stock
     * @return the stored product
     * @throws ConflictException when the SKU already exists
     */
    @Transactional
    public Product create(String sku, String name, String description, BigDecimal price, int availableQuantity) {
        String normalisedSku = sku.trim();
        if (products.existsBySku(normalisedSku)) {
            throw new ConflictException("SKU is already in use: " + normalisedSku);
        }
        Product product = products.saveAndFlush(
                Product.create(normalisedSku, name.trim(), trimToNull(description), price, availableQuantity));
        log.info("Created product {} ({})", product.getId(), product.getSku());
        return product;
    }

    /**
     * Updates the editable fields. Stock is not one of them — it moves through
     * {@link #adjustStock(UUID, int)} and through reservations, so an inattentive catalog edit
     * cannot silently wipe out stock somebody is holding.
     *
     * @param id          product to update
     * @param name        new name
     * @param description new description, may be {@code null}
     * @param price       new price
     * @return the updated product
     * @throws NotFoundException when no product has that id
     */
    @Transactional
    public Product update(UUID id, String name, String description, BigDecimal price) {
        Product product = get(id);
        product.updateDetails(name.trim(), trimToNull(description), price);
        return products.saveAndFlush(product);
    }

    /**
     * Restocks or corrects available stock.
     *
     * @param id    product to adjust
     * @param delta units to add (positive) or remove (negative)
     * @return the updated product
     * @throws NotFoundException when no product has that id
     * @throws ConflictException when the result would be negative; reserved units are held in a
     *                          separate column, so they are never consumed by a correction
     */
    @Transactional
    public Product adjustStock(UUID id, int delta) {
        Product product = get(id);
        if (!product.adjustAvailable(delta)) {
            throw new ConflictException("Stock of %s cannot be changed by %d: available is %d"
                    .formatted(product.getSku(), delta, product.getAvailableQuantity()));
        }
        log.info("Adjusted stock of {} by {} to {}", product.getSku(), delta, product.getAvailableQuantity());
        return products.saveAndFlush(product);
    }

    /**
     * Removes a product from the catalog together with its historic reservation rows.
     *
     * @param id product to delete
     * @throws NotFoundException when no product has that id
     * @throws ConflictException when an order is currently holding stock of it
     */
    @Transactional
    public void delete(UUID id) {
        Product product = get(id);
        if (reservations.existsByProductIdAndStatus(product.getId(), ReservationStatus.RESERVED)) {
            throw new ConflictException(
                    "Product " + product.getSku() + " still has reserved stock and cannot be deleted");
        }
        // Released and committed rows are history of a product that no longer exists; the foreign
        // key means they have to go first.
        reservations.deleteByProductId(product.getId());
        products.delete(product);
        log.info("Deleted product {} ({})", product.getId(), product.getSku());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
