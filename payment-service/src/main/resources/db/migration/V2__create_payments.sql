-- Phase 6: payment-service gets its own store, plus the two shared reliability tables.
--
-- One row per order, and the UNIQUE constraint on order_id is the point of the whole table: a
-- redelivered ProcessPayment must never turn into a second charge. The de-duplication log below
-- catches that first, the constraint catches it if two deliveries ever raced past the log.
CREATE TABLE payments
(
    id             UUID           PRIMARY KEY,
    order_id       UUID           NOT NULL UNIQUE,
    -- Who was charged. Copied from the command, because payment-service may not read order_db and
    -- still has to answer "is this payment yours?" for GET /api/payments/order/{orderId}.
    customer_id    UUID           NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    currency       VARCHAR(3)     NOT NULL,
    status         VARCHAR(20)    NOT NULL
                                  CHECK (status IN ('AUTHORIZED', 'FAILED', 'REFUNDED')),
    -- Why the simulated gateway declined; NULL for a successful charge.
    failure_reason VARCHAR(500),
    -- What a real provider would hand back as its own reference for the transaction.
    provider_ref   VARCHAR(100),
    -- JPA @Version: a refund and a redelivered charge cannot both win.
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- "the payments of this customer, newest first" for the visibility endpoint and for support.
CREATE INDEX idx_payments_customer_created ON payments (customer_id, created_at DESC);

-- The transactional outbox: same shape as the other services', because it is the same mapping and
-- the same relay from service-support. The charge and the PaymentCompleted/PaymentFailed event that
-- announces it are one local transaction.
CREATE TABLE outbox_events
(
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    -- The order id, so every message about one order keeps one Kafka partition.
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    topic          VARCHAR(200) NOT NULL,
    payload        JSONB        NOT NULL,
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     TEXT
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at, id) WHERE published_at IS NULL;

CREATE INDEX idx_outbox_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;

-- The idempotent-consumer log. Nowhere in this system does a duplicate delivery cost more than
-- here, which is why the check happens before the gateway is ever called.
CREATE TABLE processed_events
(
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
