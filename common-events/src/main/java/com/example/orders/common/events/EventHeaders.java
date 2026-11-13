package com.example.orders.common.events;

/**
 * Kafka record headers every event in this system carries.
 *
 * <p>The values are duplicates of fields inside the {@link EventEnvelope} body on purpose: a
 * consumer, a dead-letter inspector or a {@code kafka-console-consumer} can route, filter and
 * correlate a message without deserialising it first.
 */
public final class EventHeaders {

    /** Unique id of the event; the value consumers de-duplicate on. */
    public static final String EVENT_ID = "eventId";

    /** Payload type name, e.g. {@code OrderCreated}. */
    public static final String EVENT_TYPE = "eventType";

    /** Correlation id of the request that ultimately caused the event. */
    public static final String CORRELATION_ID = "correlationId";

    private EventHeaders() {
    }
}
