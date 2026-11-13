-- Phase: observability. W3C trace context of the transaction that wrote the event.
ALTER TABLE outbox_events ADD COLUMN traceparent VARCHAR(55);
