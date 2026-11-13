package com.example.orders.support.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * The note a consumer leaves behind saying "I have already handled this event", mapped to
 * {@code processed_events}.
 *
 * <p>The primary key is (event id, consumer): the same event may legitimately be handled by several
 * consumers inside one service, and each of them has to de-duplicate for itself.
 *
 * <p>The row is written in the same transaction as the state change it guards. That is the whole
 * mechanism: "handled" and "effect" commit together, so a redelivery after a crash either finds the
 * note and does nothing, or finds neither and does the work.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @EmbeddedId
    private Key key;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private OffsetDateTime processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    private ProcessedEvent(Key key) {
        this.key = key;
        this.processedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * @param eventId  {@code eventId} from the envelope
     * @param consumer logical consumer name, e.g. {@code order.payment-events}
     * @return a row ready to be inserted
     */
    public static ProcessedEvent of(UUID eventId, String consumer) {
        return new ProcessedEvent(new Key(eventId, consumer));
    }

    public Key getKey() {
        return key;
    }

    public UUID getEventId() {
        return key == null ? null : key.getEventId();
    }

    public String getConsumer() {
        return key == null ? null : key.getConsumer();
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }

    /** Composite primary key: one row per event and consumer. */
    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "event_id", nullable = false, updatable = false)
        private UUID eventId;

        @Column(name = "consumer", nullable = false, length = 100, updatable = false)
        private String consumer;

        protected Key() {
            // for JPA
        }

        /**
         * @param eventId  event id
         * @param consumer logical consumer name
         */
        public Key(UUID eventId, String consumer) {
            this.eventId = eventId;
            this.consumer = consumer;
        }

        public UUID getEventId() {
            return eventId;
        }

        public String getConsumer() {
            return consumer;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(eventId, key.eventId) && Objects.equals(consumer, key.consumer);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumer);
        }

        @Override
        public String toString() {
            return consumer + "/" + eventId;
        }
    }
}
