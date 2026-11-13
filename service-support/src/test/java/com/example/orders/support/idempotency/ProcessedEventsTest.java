package com.example.orders.support.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.orders.support.SupportIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The de-duplication log: the note is only worth something if it shares the fate of the state change
 * it guards, so that is what these tests pin down.
 */
class ProcessedEventsTest extends SupportIntegrationTest {

    private static final String CONSUMER = "test.consumer";

    @Autowired
    private ProcessedEvents processedEvents;

    @Autowired
    private ProcessedEventRepository repository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetState() {
        repository.deleteAllInBatch();
    }

    @Test
    void theFirstDeliveryClaimsTheEventAndTheSecondIsANoOp() {
        UUID eventId = UUID.randomUUID();

        boolean first = claim(eventId, CONSUMER);
        boolean second = claim(eventId, CONSUMER);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(processedEvents.isProcessed(eventId, CONSUMER)).isTrue();
    }

    @Test
    void oneEventIsClaimedSeparatelyByEveryConsumer() {
        UUID eventId = UUID.randomUUID();

        boolean claimedByFirst = claim(eventId, "order.payment-events");
        // A second listener in the same service legitimately has to handle the same event.
        boolean claimedBySecond = claim(eventId, "notification.payments");

        assertThat(claimedByFirst).isTrue();
        assertThat(claimedBySecond).isTrue();

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void aRollbackLeavesNoNoteBehindSoTheRedeliveryDoesTheWork() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(processedEvents.markProcessed(eventId, CONSUMER)).isTrue();
            throw new IllegalStateException("the state change failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(processedEvents.isProcessed(eventId, CONSUMER)).isFalse();
        // And the redelivery is free to do the work for real.
        assertThat(claim(eventId, CONSUMER)).isTrue();
    }

    /**
     * @param eventId  event id
     * @param consumer logical consumer name
     * @return whether the claim was new, taken in its own transaction as a consumer would
     */
    private boolean claim(UUID eventId, String consumer) {
        Boolean claimed = transactions.execute(status -> processedEvents.markProcessed(eventId, consumer));
        return Boolean.TRUE.equals(claimed);
    }

    @Test
    void claimingAnEventOutsideATransactionIsRefused() {
        // Without this guard a consumer could record "handled" for work that later rolls back — and
        // then never do it, because the redelivery would find the note.
        assertThatThrownBy(() -> processedEvents.markProcessed(UUID.randomUUID(), CONSUMER))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void theNoteIsWrittenWithTheEventIdAndTheConsumerAsItsKey() {
        UUID eventId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> processedEvents.markProcessed(eventId, CONSUMER));

        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Long.class, eventId, CONSUMER);
        assertThat(rows).isEqualTo(1);
        ProcessedEvent stored = repository.findById(new ProcessedEvent.Key(eventId, CONSUMER)).orElseThrow();
        assertThat(stored.getEventId()).isEqualTo(eventId);
        assertThat(stored.getConsumer()).isEqualTo(CONSUMER);
        assertThat(stored.getProcessedAt()).isNotNull();
    }

    @Test
    void theRetentionSweepDropsOldNotesAndKeepsRecentOnes() {
        UUID oldEvent = UUID.randomUUID();
        UUID recentEvent = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            processedEvents.markProcessed(oldEvent, CONSUMER);
            processedEvents.markProcessed(recentEvent, CONSUMER);
        });
        jdbc.update("UPDATE processed_events SET processed_at = ? WHERE event_id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(30), oldEvent);

        assertThat(processedEvents.purgeOlderThan(Duration.ofDays(7))).isEqualTo(1);

        assertThat(processedEvents.isProcessed(oldEvent, CONSUMER)).isFalse();
        assertThat(processedEvents.isProcessed(recentEvent, CONSUMER)).isTrue();
    }
}
