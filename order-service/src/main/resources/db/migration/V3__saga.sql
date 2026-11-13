-- Phase 6: the order saga's own state.
--
-- Three things arrive here. The status set grows from "accepted/finished" to the actual saga
-- states, the order remembers the two foreign facts it needs in order to compensate (which
-- reservation is holding its stock, which payment took its money), and the service gets the
-- de-duplication log every consumer in this system needs.

-- CANCELLATION_PENDING is 20 characters exactly; widen the column rather than live on the edge.
ALTER TABLE orders ALTER COLUMN status TYPE VARCHAR(30);

-- V2 declared the check inline, so Postgres named it orders_status_check.
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE orders
    ADD CONSTRAINT orders_status_check CHECK (status IN (
        'PENDING',              -- accepted, OrderCreated published, nobody has answered yet
        'STOCK_RESERVED',       -- inventory said yes (kept for manual repair and future splits)
        'PAYMENT_PENDING',      -- stock held, ProcessPayment on its way to payment-service
        'CONFIRMED',            -- paid; stock committed
        'CANCELLATION_PENDING', -- customer cancelled a confirmed order, refund not back yet
        'CANCELLED',            -- undone after it had progressed (payment failed, cancelled, timeout)
        'REJECTED'              -- turned down before anything moved (no stock)
        ));

-- What the compensating steps need. They are ids of rows in *other* services' databases, so they
-- are plain columns without a foreign key: order-service may not read inventory_db or payment_db,
-- it can only ask those services to undo something.
ALTER TABLE orders ADD COLUMN reservation_id UUID;
ALTER TABLE orders ADD COLUMN payment_id UUID;

-- The saga-timeout sweeper's query: orders that have not reached a final state. Partial, so it
-- stays the size of the in-flight set rather than the size of the history.
CREATE INDEX idx_orders_unfinished ON orders (updated_at)
    WHERE status IN ('PENDING', 'STOCK_RESERVED', 'PAYMENT_PENDING', 'CANCELLATION_PENDING');

-- The idempotent-consumer log. One row per (event, consumer): order-service listens to several
-- topics, and each listener has to de-duplicate for itself.
--
-- The row is written in the same transaction as the state change it guards, which is what turns
-- Kafka's at-least-once delivery into an at-most-once *effect*: a redelivery either finds the row
-- and does nothing, or finds neither the row nor the effect and does the work for real.
CREATE TABLE processed_events
(
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

-- The retention sweep's query.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
