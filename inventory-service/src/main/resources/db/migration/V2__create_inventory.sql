-- Phase 4: the inventory-service catalog and its stock reservations.
--
-- available_quantity and reserved_quantity are kept apart on purpose: "available" is what a new
-- order may still take, "reserved" is what earlier orders are holding. Reserving moves units from
-- one column to the other, so a stock correction can never eat somebody else's reservation, and
-- the CHECK constraints make overselling impossible at the storage layer too - not just in Java.
CREATE TABLE products
(
    id                 UUID           PRIMARY KEY,
    sku                VARCHAR(64)    NOT NULL UNIQUE,
    name               VARCHAR(200)   NOT NULL,
    description        TEXT,
    price              NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    available_quantity INT            NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity  INT            NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    -- JPA @Version: the optimistic-locking guard against two concurrent stock changes.
    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- One row per (order, product). The UNIQUE constraint is the final guard for idempotency: even if
-- two copies of the same OrderCreated event are handled at the same time (phase 6 redelivery), the
-- second insert cannot succeed, so stock is never reserved twice for one order line.
CREATE TABLE stock_reservations
(
    id         UUID        PRIMARY KEY,
    order_id   UUID        NOT NULL,
    product_id UUID        NOT NULL REFERENCES products (id),
    quantity   INT         NOT NULL CHECK (quantity > 0),
    status     VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED', 'RELEASED', 'COMMITTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_reservations_order_product UNIQUE (order_id, product_id)
);

-- Lookups the service actually performs: reservations of one order (saga steps) and the live
-- reservations of one product (blocking a product delete).
CREATE INDEX idx_stock_reservations_order ON stock_reservations (order_id);
CREATE INDEX idx_stock_reservations_product_status ON stock_reservations (product_id, status);
