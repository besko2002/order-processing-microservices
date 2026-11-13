package com.example.orders.support.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One event waiting to go to Kafka, mapped to {@code outbox_events}.
 *
 * <p>The row is written in the <em>same</em> transaction as the state change it announces, which
 * is the whole point of the pattern: committing to Postgres and publishing to Kafka cannot be one
 * atomic act, so the event is first made durable next to the data and only then relayed.
 *
 * <p>{@link #getId()} is also the {@code eventId} inside the stored envelope. The relay is
 * at-least-once — a broker acknowledgement can be lost after the send succeeded — so consumers
 * de-duplicate on exactly this value (see {@code com.example.orders.support.idempotency}).
 *
 * <p>Each service owns its own {@code outbox_events} table through its own Flyway migration; this
 * module only owns the mapping and the machinery around it.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    /** {@code aggregate_type} used when a service does not configure one. */
    public static final String DEFAULT_AGGREGATE_TYPE = "Aggregate";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    /** Kafka message key: every event about one order lands on one partition, so order is kept. */
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 200, updatable = false)
    private String topic;

    /** The serialised {@code EventEnvelope}; the relay ships these bytes unchanged. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    /** W3C trace context of the transaction that wrote the event, so the trace continues in Kafka. */
    @Column(name = "traceparent", length = 55, updatable = false)
    private String traceparent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
        // for JPA
    }

    private OutboxEvent(UUID eventId, String aggregateType, UUID aggregateId, String eventType, String topic,
            String payload, String correlationId) {
        this.id = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.correlationId = correlationId;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.attempts = 0;
    }

    /**
     * @param eventId       id of the event, identical to the {@code eventId} inside {@code payload}
     * @param aggregateType which kind of aggregate the event is about, e.g. {@code Order}
     * @param aggregateId   id of the aggregate the event is about; also the Kafka key
     * @param eventType     payload type name, e.g. {@code OrderCreated}
     * @param topic         destination topic
     * @param payload       serialised envelope
     * @param correlationId correlation id of the request that caused it, may be {@code null}
     * @return an unpublished outbox row
     */
    public static OutboxEvent pending(UUID eventId, String aggregateType, UUID aggregateId, String eventType,
            String topic, String payload, String correlationId) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, topic, payload, correlationId);
    }

    /** Marks the row as delivered; the relay calls this only after the broker acknowledged. */
    public void markPublished() {
        this.publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.lastError = null;
    }

    /**
     * Records a failed delivery. The row stays unpublished and is picked up by the next poll —
     * retrying forever is deliberate: an event that is already committed to the database has to
     * reach the broker eventually, and dropping it would silently break the saga.
     *
     * @param error short description of what went wrong
     */
    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayload() {
        return payload;
    }

    public String getTraceparent() {
        return traceparent;
    }

    /** @param traceparent W3C {@code traceparent} value, may be {@code null} */
    public void setTraceparent(String traceparent) {
        this.traceparent = traceparent;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
