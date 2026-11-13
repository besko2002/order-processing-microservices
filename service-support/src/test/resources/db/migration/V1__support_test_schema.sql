-- Schema for the reliability tests of this module. Byte for byte the tables the real services
-- create in their own migrations: if this module's mapping and the services' DDL ever drift apart,
-- these tests are where it shows.
CREATE TABLE outbox_events
(
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    topic          VARCHAR(200) NOT NULL,
    payload        JSONB        NOT NULL,
    correlation_id VARCHAR(64),
    traceparent VARCHAR(55),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     TEXT
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at, id) WHERE published_at IS NULL;

CREATE INDEX idx_outbox_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;

CREATE TABLE processed_events
(
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
