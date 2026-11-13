package com.example.orders.support.outbox;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.support.correlation.CorrelationId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an event into the outbox as part of whatever transaction is already running.
 *
 * <p>{@code Propagation.MANDATORY} is the guard rail that makes the pattern hold: recording an
 * event outside the transaction that changed the state would recreate the very failure mode the
 * outbox exists to prevent, so a caller that forgot the transaction fails loudly at once instead
 * of silently losing atomicity in production.
 *
 * <p>The correlation id is taken from the MDC, which is filled by the HTTP filter for a REST call
 * and by the Kafka record interceptor for a consumed event. That is how one id reaches every hop of
 * the saga without being threaded through every method signature.
 */
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final String defaultAggregateType;

    /**
     * @param outbox               where the rows go
     * @param json                 the application's mapper, so the payload looks like every other
     *                             JSON this service produces
     * @param defaultAggregateType {@code aggregate_type} used by {@link #record(UUID, String,
     *                             String, Object)}
     */
    public OutboxWriter(OutboxRepository outbox, ObjectMapper json, String defaultAggregateType) {
        this.outbox = outbox;
        this.json = json;
        this.defaultAggregateType = defaultAggregateType;
    }

    /**
     * @param aggregateId id of the aggregate the event is about; becomes the Kafka message key
     * @param eventType   payload type name, e.g. {@code OrderCreated}
     * @param topic       destination topic
     * @param payload     the event record from {@code common-events}
     * @return the stored, still unpublished row; its id is the envelope's {@code eventId}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(UUID aggregateId, String eventType, String topic, Object payload) {
        return record(defaultAggregateType, aggregateId, eventType, topic, payload);
    }

    /**
     * @param aggregateType which kind of aggregate the event is about
     * @param aggregateId   id of the aggregate; becomes the Kafka message key
     * @param eventType     payload type name
     * @param topic         destination topic
     * @param payload       the event record from {@code common-events}
     * @return the stored, still unpublished row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(
            String aggregateType, UUID aggregateId, String eventType, String topic, Object payload) {
        // One id for both: the row the relay retries and the event consumers de-duplicate on are
        // the same thing, so they must not be two different numbers.
        UUID eventId = UUID.randomUUID();
        String correlationId = CorrelationId.sanitise(MDC.get(CorrelationId.MDC_KEY));
        EventEnvelope<Object> envelope =
                new EventEnvelope<>(eventId, eventType, Instant.now(), correlationId, payload);
        OutboxEvent event = OutboxEvent.pending(
                eventId, aggregateType, aggregateId, eventType, topic, serialise(envelope), correlationId);
        event.setTraceparent(currentTraceparent());
        return outbox.save(event);
    }

    /**
     * The relay publishes later from a scheduler thread, so the active trace is captured here (Micrometer
     * Tracing keeps {@code traceId}/{@code spanId} in the MDC) and sent as a W3C {@code traceparent} header.
     */
    static String currentTraceparent() {
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        if (traceId == null || spanId == null
                || !traceId.matches("[0-9a-f]{32}") || !spanId.matches("[0-9a-f]{16}")) {
            return null;
        }
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private String serialise(EventEnvelope<Object> envelope) {
        try {
            return json.writeValueAsString(envelope);
        }
        catch (JsonProcessingException e) {
            // A payload record that cannot be serialised is a programming error, not a runtime
            // condition: fail the transaction rather than commit a state change nobody will hear
            // about.
            throw new IllegalStateException("Could not serialise event " + envelope.eventType(), e);
        }
    }
}
