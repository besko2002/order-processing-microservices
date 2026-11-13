package com.example.orders.support.idempotency;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a consumer idempotent: it records that an event id has been handled, in the very same
 * transaction as the state change it caused.
 *
 * <p>Kafka delivers at least once — the outbox relay may replay a record, a consumer may crash after
 * doing the work but before committing its offset, a rebalance may hand a partition to another
 * instance. Every consumer therefore has to answer "have I already done this?" and the only honest
 * place to keep that answer is next to the data it changed:
 *
 * <pre>
 * &#64;Transactional
 * void handle(EventEnvelope&lt;StockReserved&gt; event) {
 *     if (!processedEvents.markProcessed(event.eventId(), CONSUMER)) {
 *         return;                       // duplicate delivery, nothing to do
 *     }
 *     ...                               // the state change
 * }
 * </pre>
 *
 * <p>{@code Propagation.MANDATORY} enforces that: called without a surrounding transaction it fails
 * immediately instead of recording "handled" for work that may still roll back. And because the note
 * is part of that transaction, a rollback leaves no note behind — the redelivery then does the work
 * for real.
 */
public class ProcessedEvents {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEvents.class);

    private final ProcessedEventRepository repository;

    /**
     * @param repository the {@code processed_events} table of this service
     */
    public ProcessedEvents(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Claims an event for a consumer.
     *
     * @param eventId  {@code eventId} from the envelope; never {@code null} for a message of this
     *                 system
     * @param consumer logical consumer name, e.g. {@code order.inventory-events}
     * @return {@code true} when this is the first delivery and the caller should do the work;
     *         {@code false} when it was handled before and the caller must do nothing
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String consumer) {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId must not be null");
        }
        ProcessedEvent.Key key = new ProcessedEvent.Key(eventId, consumer);
        if (repository.existsById(key)) {
            log.debug("Event {} was already processed by {}, ignoring the duplicate delivery", eventId, consumer);
            return false;
        }
        // Flush now: if two deliveries of the same event are handled at the same moment, the
        // primary key decides, and the loser has to fail here rather than at commit time.
        repository.saveAndFlush(ProcessedEvent.of(eventId, consumer));
        return true;
    }

    /**
     * @param eventId  event id
     * @param consumer logical consumer name
     * @return whether that consumer has already handled the event
     */
    @Transactional(readOnly = true)
    public boolean isProcessed(UUID eventId, String consumer) {
        return eventId != null && repository.existsById(new ProcessedEvent.Key(eventId, consumer));
    }

    /**
     * Drops notes that are older than any possible redelivery.
     *
     * @param retention how long a note is kept
     * @return how many rows were removed
     */
    @Transactional
    public int purgeOlderThan(Duration retention) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(retention);
        int removed = repository.deleteProcessedBefore(cutoff);
        if (removed > 0) {
            log.info("Removed {} processed-event note(s) older than {}", removed, cutoff);
        }
        return removed;
    }
}
