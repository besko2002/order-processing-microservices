-- Notification history plus the idempotency table used by service-support.

CREATE TABLE notifications
(
    id          UUID         PRIMARY KEY,
    event_id    UUID         NOT NULL UNIQUE,
    customer_id UUID         NOT NULL,
    order_id    UUID         NOT NULL,
    type        VARCHAR(30)  NOT NULL CHECK (type IN ('ORDER_CONFIRMED', 'ORDER_CANCELLED', 'ORDER_REJECTED')),
    channel     VARCHAR(20)  NOT NULL CHECK (channel IN ('EMAIL')),
    recipient   VARCHAR(255) NOT NULL,
    subject     VARCHAR(255) NOT NULL,
    body        TEXT         NOT NULL,
    status      VARCHAR(20)  NOT NULL CHECK (status IN ('SENT', 'SKIPPED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_customer ON notifications (customer_id, created_at DESC);

CREATE TABLE processed_events
(
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
