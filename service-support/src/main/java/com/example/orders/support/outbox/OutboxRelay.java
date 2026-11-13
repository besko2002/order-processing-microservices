package com.example.orders.support.outbox;

import com.example.orders.common.events.EventHeaders;
import com.example.orders.support.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves committed events out of {@code outbox_events} and onto Kafka.
 *
 * <p><strong>Several instances at once.</strong> The batch is claimed with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}: a second relay running in another pod steps over the
 * rows this one holds instead of waiting for them or publishing them again. No leader election, no
 * distributed lock, no single point of failure — the database already knows how to arbitrate.
 *
 * <p><strong>Delivery is at-least-once.</strong> "The broker acknowledged" and "the row is marked
 * published" are two systems and cannot be committed atomically, so a crash in between replays the
 * event on the next poll. That is why the event id is the outbox row id: consumers de-duplicate on
 * it and the effect stays exactly-once even though the delivery is not.
 *
 * <p><strong>Order per aggregate is preserved.</strong> Rows go out oldest first, and if one event
 * of an aggregate fails, every later event of that <em>same</em> aggregate is left for the next poll
 * rather than overtaking it. Other aggregates in the batch are unaffected, so one poison row
 * cannot stall the whole system.
 *
 * <p><strong>Failures are never dropped.</strong> A failed send increments {@code attempts},
 * records {@code last_error} and leaves the row unpublished; the next poll picks it up again.
 * There is no maximum attempt count on purpose: the event is already part of a committed business
 * fact, so the only correct outcome is that it eventually reaches the broker.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;

    /**
     * @param outbox      the queue table
     * @param kafka       string producer configured for at-least-once delivery
     * @param batchSize   rows claimed per poll
     * @param sendTimeout how long one send may take before the attempt is recorded as failed
     * @param retention   how long published rows are kept as an audit trail
     */
    public OutboxRelay(
            OutboxRepository outbox,
            KafkaTemplate<String, String> kafka,
            int batchSize,
            Duration sendTimeout,
            Duration retention) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
    }

    /**
     * Publishes one batch. Everything happens in a single transaction, so the row locks taken by
     * {@code SKIP LOCKED} are only released once the outcome of each send has been written down.
     *
     * @return how many events reached the broker in this batch
     */
    @Transactional
    public int publishPending() {
        List<OutboxEvent> batch = outbox.lockNextUnpublished(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        Set<UUID> stalledAggregates = new HashSet<>();
        int published = 0;
        for (OutboxEvent event : batch) {
            if (stalledAggregates.contains(event.getAggregateId())) {
                // An earlier event of this aggregate is still stuck; sending this one now would
                // deliver its history out of sequence.
                continue;
            }
            if (publish(event)) {
                published++;
            }
            else {
                stalledAggregates.add(event.getAggregateId());
            }
        }
        if (published > 0) {
            log.debug("Outbox relay published {} of {} claimed event(s)", published, batch.size());
        }
        return published;
    }

    /**
     * Drops published rows older than the configured retention. They are kept for a while on
     * purpose — they are the audit trail of what this service told the rest of the system — but the
     * table is a queue, not an archive.
     *
     * @return how many rows were removed
     */
    @Transactional
    public int purgePublished() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(retention);
        int removed = outbox.deletePublishedBefore(cutoff);
        if (removed > 0) {
            log.info("Outbox cleanup removed {} event(s) published before {}", removed, cutoff);
        }
        return removed;
    }

    private boolean publish(OutboxEvent event) {
        String previousCorrelationId = MDC.get(CorrelationId.MDC_KEY);
        if (event.getCorrelationId() != null) {
            // Log the send under the correlation id of the request that produced the event, not
            // under the scheduler's (empty) one.
            MDC.put(CorrelationId.MDC_KEY, event.getCorrelationId());
        }
        try {
            kafka.send(toRecord(event)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished();
            return true;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.markFailed("Interrupted while waiting for the broker");
            log.warn("Interrupted while publishing outbox event {}", event.getId());
            return false;
        }
        catch (Exception e) {
            event.markFailed(describe(e));
            log.warn("Outbox event {} ({} for aggregate {}) could not be published, attempt {}: {}",
                    event.getId(), event.getEventType(), event.getAggregateId(), event.getAttempts(),
                    describe(e));
            return false;
        }
        finally {
            if (previousCorrelationId == null) {
                MDC.remove(CorrelationId.MDC_KEY);
            }
            else {
                MDC.put(CorrelationId.MDC_KEY, previousCorrelationId);
            }
        }
    }

    private static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.getTopic(),
                // Key = aggregate id: all events of one order share a partition, so the broker
                // itself keeps them in order for every consumer group.
                event.getAggregateId().toString(),
                event.getPayload());
        record.headers().add(EventHeaders.EVENT_ID, bytes(event.getId().toString()));
        record.headers().add(EventHeaders.EVENT_TYPE, bytes(event.getEventType()));
        if (event.getCorrelationId() != null) {
            record.headers().add(EventHeaders.CORRELATION_ID, bytes(event.getCorrelationId()));
        }
        if (event.getTraceparent() != null) {
            record.headers().add("traceparent", bytes(event.getTraceparent()));
        }
        return record;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String describe(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
