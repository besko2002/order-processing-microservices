-- Phase 5: orders, their line snapshots and the transactional outbox.
--
-- The three tables belong to one story. An order is only ever accepted together with the event
-- that announces it, and both live in this one database, so a single local transaction is enough:
-- either the order, its items and the outbox row are all there, or none of them is. Kafka is
-- never touched while that transaction is open - a separate relay does that afterwards - which is
-- exactly what rules out "order saved but event lost" and "event published but order rolled back".
CREATE TABLE orders
(
    id              UUID           PRIMARY KEY,
    user_id         UUID           NOT NULL,
    status          VARCHAR(20)    NOT NULL
                                   CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'CANCELLED')),
    total_amount    NUMERIC(12, 2) NOT NULL CHECK (total_amount >= 0),
    currency        VARCHAR(3)     NOT NULL,
    -- Optional client-supplied replay guard. NULL for ordinary orders, and Postgres treats NULLs
    -- as distinct in a UNIQUE constraint, so unkeyed orders never collide with each other.
    idempotency_key VARCHAR(100),
    -- Filled by the saga in phase 6 when stock or payment turns the order down.
    failure_reason  VARCHAR(500),
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_orders_user_idempotency UNIQUE (user_id, idempotency_key)
);

-- "my orders, newest first" and the admin list filtered by status: both are index-only lookups.
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);
CREATE INDEX idx_orders_status_created ON orders (status, created_at DESC);

-- A price snapshot, not a pointer into the catalog: unit_price and product_name are copied from
-- inventory-service at the moment the order is placed, so a later price change or rename can never
-- silently rewrite what a customer agreed to pay. line_total is stored rather than computed for
-- the same reason - the arithmetic that produced the order total stays auditable.
CREATE TABLE order_items
(
    id           UUID           PRIMARY KEY,
    order_id     UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id   UUID           NOT NULL,
    product_name VARCHAR(200)   NOT NULL,
    unit_price   NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    quantity     INT            NOT NULL CHECK (quantity > 0),
    line_total   NUMERIC(12, 2) NOT NULL CHECK (line_total >= 0),
    -- Duplicate product ids in a request are merged into one line before they get here; this is
    -- the storage-level guarantee that they really were.
    CONSTRAINT uq_order_items_order_product UNIQUE (order_id, product_id)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

-- The outbox. id IS the event id carried in the envelope, so a consumer that stores processed
-- event ids (phase 6) de-duplicates against the very same value the relay may have sent twice:
-- the relay is at-least-once by design, since "row published" and "broker acknowledged" cannot be
-- committed atomically across two systems.
CREATE TABLE outbox_events
(
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    topic          VARCHAR(200) NOT NULL,
    -- The serialised envelope, byte for byte what the relay puts on the topic.
    payload        JSONB        NOT NULL,
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     TEXT
);

-- The relay's only query: the oldest rows that still have to go out. Partial, so the index stays
-- the size of the backlog instead of the size of the history - normally a handful of rows.
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at, id) WHERE published_at IS NULL;

-- The cleanup job's query: published rows old enough to drop.
CREATE INDEX idx_outbox_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;
