-- Phase 6: inventory-service becomes an event consumer and an event producer.
--
-- Both tables are this service's own, in this service's own database, even though the code that uses
-- them is shared (service-support). "Database per service" is what makes the outbox work at all: the
-- reservation and the event announcing it have to be one local transaction, which is only possible
-- if both live in the same database as the stock they are about.

-- Same shape as order-service's outbox, deliberately: one mapping, one relay, one set of guarantees.
CREATE TABLE outbox_events
(
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    -- The order id, so every message about one order keeps one Kafka partition and stays in order.
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

-- The relay's only query: the oldest rows that still have to go out. Partial, so the index stays the
-- size of the backlog instead of the size of the history.
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at, id) WHERE published_at IS NULL;

-- The cleanup job's query: published rows old enough to drop.
CREATE INDEX idx_outbox_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;

-- The idempotent-consumer log. Reserving stock twice for one order would be the most expensive
-- mistake this service could make, and UNIQUE(order_id, product_id) on stock_reservations is only the
-- last line of defence; this table is the first, and it also covers the commands (a redelivered
-- ReleaseStock must not return the same units twice).
CREATE TABLE processed_events
(
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
