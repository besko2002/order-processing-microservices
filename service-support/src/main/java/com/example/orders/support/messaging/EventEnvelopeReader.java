package com.example.orders.support.messaging;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.EventTypes;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/**
 * Turns a raw Kafka record into a typed {@link EventEnvelope}.
 *
 * <p>Records are consumed as plain strings and parsed here rather than by a configured
 * {@code JsonDeserializer}, for three reasons:
 * <ul>
 *   <li><b>The payload type is chosen by us.</b> The {@code eventType} header (or the field, when a
 *       producer only wrote the body) is looked up in {@link EventTypes}. Nothing in the message can
 *       name a class to instantiate, which is the difference between deserialising data and letting
 *       a topic writer pick code paths.</li>
 *   <li><b>A malformed record fails where we can classify it.</b> The failure becomes a
 *       {@link PoisonEventException}, which the error handler dead-letters without retrying,
 *       instead of a deserialisation error thrown deep inside the container.</li>
 *   <li><b>Unknown fields are tolerated.</b> A producer that adds a field to a payload must not
 *       break every consumer that has not been rebuilt yet.</li>
 * </ul>
 */
public class EventEnvelopeReader {

    private final ObjectMapper json;

    /**
     * @param json the application's mapper; a copy is taken and made tolerant of unknown fields
     */
    public EventEnvelopeReader(ObjectMapper json) {
        this.json = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * @param record record as it arrived
     * @return the envelope with its payload deserialised into the registered record type
     * @throws PoisonEventException when the record is not a message of this system
     */
    public EventEnvelope<Object> read(ConsumerRecord<String, String> record) {
        String body = record.value();
        if (body == null || body.isBlank()) {
            throw new PoisonEventException("Record has an empty body");
        }
        JsonNode envelope = parse(body);
        String eventType = firstNonBlank(header(record, EventHeaders.EVENT_TYPE), text(envelope, "eventType"));
        Class<?> payloadType = EventTypes.payloadType(eventType)
                .orElseThrow(() -> new PoisonEventException(
                        "Unknown event type '" + eventType + "' on topic " + record.topic()));

        UUID eventId = uuid(firstNonBlank(header(record, EventHeaders.EVENT_ID), text(envelope, "eventId")));
        if (eventId == null) {
            throw new PoisonEventException("Record of type " + eventType + " carries no usable eventId");
        }
        String correlationId = firstNonBlank(
                header(record, EventHeaders.CORRELATION_ID), text(envelope, "correlationId"));

        JsonNode payloadNode = envelope.get("payload");
        if (payloadNode == null || payloadNode.isNull()) {
            throw new PoisonEventException("Record of type " + eventType + " carries no payload");
        }
        Object payload;
        try {
            payload = json.treeToValue(payloadNode, payloadType);
        }
        catch (Exception e) {
            throw new PoisonEventException(
                    "Payload of " + eventType + " does not match " + payloadType.getSimpleName(), e);
        }
        return new EventEnvelope<>(eventId, eventType, occurredAt(envelope), correlationId, payload);
    }

    /**
     * @param record record as it arrived
     * @return the {@code correlationId} header, or {@code null} when the producer did not set one
     */
    public static String correlationIdOf(ConsumerRecord<?, ?> record) {
        return header(record, EventHeaders.CORRELATION_ID);
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) {
                throw new PoisonEventException("Record body is not a JSON object");
            }
            return node;
        }
        catch (PoisonEventException e) {
            throw e;
        }
        catch (Exception e) {
            throw new PoisonEventException("Record body is not valid JSON", e);
        }
    }

    private static Instant occurredAt(JsonNode envelope) {
        String value = text(envelope, "occurredAt");
        if (value == null) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        }
        catch (RuntimeException e) {
            return Instant.now();
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }
}
