package com.example.orders.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Wrapper carried by every message on Kafka.
 *
 * <p>{@code eventId} is the idempotency key consumers store in their {@code processed_events}
 * table; {@code correlationId} is propagated from the API gateway through HTTP and Kafka headers.
 *
 * @param <T> payload type, one of the records in this package
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String correlationId,
        T payload) {

    /** Builds an envelope with a fresh event id and the current timestamp. */
    public static <T> EventEnvelope<T> of(String eventType, String correlationId, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, Instant.now(), correlationId, payload);
    }

    /** Builds an envelope using the payload's simple class name as the event type. */
    public static <T> EventEnvelope<T> of(String correlationId, T payload) {
        return of(payload.getClass().getSimpleName(), correlationId, payload);
    }
}
