package com.example.orders.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A catalog item and its stock. Mapped to the {@code products} table from
 * {@code V2__create_inventory.sql} (the schema is owned by Flyway; {@code ddl-auto=validate} only
 * checks that this mapping still matches it).
 *
 * <p>Stock is two numbers: {@code availableQuantity} is what a new order may still take and
 * {@code reservedQuantity} is what confirmed-but-unpaid orders are holding. Reserving moves units
 * from the first to the second, releasing moves them back, committing drops them from
 * {@code reserved} for good.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sku", nullable = false, length = 64, unique = true, updatable = false)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    /** Optimistic-locking guard: two concurrent stock changes cannot both win. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Product() {
        // for JPA
    }

    private Product(String sku, String name, String description, BigDecimal price, int availableQuantity) {
        this.id = UUID.randomUUID();
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = 0;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    /**
     * @param sku               business key, unique across the catalog
     * @param name              display name
     * @param description       optional long text
     * @param price             unit price, never negative
     * @param availableQuantity units on the shelf, never negative
     * @return a new product with a freshly generated id and nothing reserved
     */
    public static Product create(
            String sku, String name, String description, BigDecimal price, int availableQuantity) {
        return new Product(sku, name, description, price, availableQuantity);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Updates the editable catalog fields. The SKU stays as it is: other services and past orders
     * refer to it, so renaming it would rewrite history.
     *
     * @param name        new display name
     * @param description new description, may be {@code null}
     * @param price       new unit price
     */
    public void updateDetails(String name, String description, BigDecimal price) {
        this.name = name;
        this.description = description;
        this.price = price;
    }

    /**
     * @param delta units to add (restock) or subtract (correction, shrinkage)
     * @return whether the change was applied; {@code false} when it would push available stock
     *         below zero, in which case nothing was modified
     */
    public boolean adjustAvailable(int delta) {
        long result = (long) this.availableQuantity + delta;
        if (result < 0 || result > Integer.MAX_VALUE) {
            return false;
        }
        this.availableQuantity = (int) result;
        return true;
    }

    /**
     * Moves units from available to reserved.
     *
     * @param quantity units to hold, must be positive
     * @return whether the hold was taken; {@code false} when there is not enough available stock
     */
    public boolean reserve(int quantity) {
        if (quantity <= 0 || quantity > this.availableQuantity) {
            return false;
        }
        this.availableQuantity -= quantity;
        this.reservedQuantity += quantity;
        return true;
    }

    /**
     * Gives a hold back to the shelf.
     *
     * @param quantity units previously reserved
     */
    public void releaseReserved(int quantity) {
        this.reservedQuantity = Math.max(0, this.reservedQuantity - quantity);
        this.availableQuantity += quantity;
    }

    /**
     * Turns a hold into a finished sale: the units leave the warehouse, so they are only dropped
     * from {@code reserved} and never come back to {@code available}.
     *
     * @param quantity units previously reserved
     */
    public void commitReserved(int quantity) {
        this.reservedQuantity = Math.max(0, this.reservedQuantity - quantity);
    }

    /**
     * Takes sold units back onto the shelf.
     *
     * <p>Needed by refunds: a committed sale that is cancelled has to become stock again, and the
     * units are no longer in {@code reserved} — {@link #commitReserved} already dropped them there.
     * This is therefore an increase of {@code available} alone, not a reversal of a reservation.
     *
     * @param quantity units coming back from a refunded order
     */
    public void returnCommitted(int quantity) {
        if (quantity <= 0) {
            return;
        }
        this.availableQuantity += quantity;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public long getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
