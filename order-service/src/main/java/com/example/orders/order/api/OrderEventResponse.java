package com.example.orders.order.api;

import com.example.orders.support.outbox.OutboxEvent;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * One event or command order-service emitted for an order, as the "behind the scenes" view shows
 * it. The payload is deliberately absent: it can carry data (payment details, for instance) that
 * the customer has no reason to see.
 *
 * @param eventType     payload type name, e.g. {@code ProcessPayment}
 * @param topic         Kafka topic it was published to
 * @param createdAt     when it was written, in the same transaction as the order's state change
 * @param publishedAt   when the relay got it onto Kafka; {@code null} while it is still waiting
 * @param attempts      failed delivery attempts so far
 * @param traceId       trace id parsed from the stored {@code traceparent}; {@code null} if absent
 *                      or malformed
 * @param correlationId correlation id of the request that caused the event, if any
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderEventResponse(
        String eventType,
        String topic,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt,
        int attempts,
        String traceId,
        String correlationId) {

    /** A version-00 {@code traceparent} has exactly 55 lowercase-hex characters and separators. */
    private static final String TRACEPARENT_PATTERN =
            "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ZERO_PARENT_ID = "0000000000000000";

    /**
     * @param event stored outbox row
     * @return its customer-facing representation
     */
    public static OrderEventResponse of(OutboxEvent event) {
        return new OrderEventResponse(
                event.getEventType(),
                event.getTopic(),
                event.getCreatedAt(),
                event.getPublishedAt(),
                event.getAttempts(),
                traceIdOf(event.getTraceparent()),
                event.getCorrelationId());
    }

    /**
     * @param traceparent W3C {@code traceparent} value, may be {@code null}
     * @return the 32-hex trace id, or {@code null} if the value is absent or not well formed
     */
    static String traceIdOf(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        if (traceparent.length() != 55 || !traceparent.matches(TRACEPARENT_PATTERN)) {
            return null;
        }
        String traceId = traceparent.substring(3, 35);
        String parentId = traceparent.substring(36, 52);
        // An all-zero trace or parent id is the W3C "invalid" value.
        return ZERO_TRACE_ID.equals(traceId) || ZERO_PARENT_ID.equals(parentId) ? null : traceId;
    }
}
