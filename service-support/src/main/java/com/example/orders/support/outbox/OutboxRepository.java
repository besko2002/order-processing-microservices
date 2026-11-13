package com.example.orders.support.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and retires outbox rows. Everything the relay needs, and nothing else. */
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the next batch of unpublished events for this poll.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes several instances of a service safe: each
     * relay locks the rows it takes, and any other relay running at the same moment steps over
     * them instead of blocking or publishing them a second time. The locks are held until the
     * surrounding transaction commits, i.e. until {@code published_at} or {@code attempts} has
     * been written.
     *
     * <p>{@code ORDER BY created_at, id} hands the events back in the order they were produced;
     * the relay additionally refuses to overtake a failed event of the same aggregate, so the
     * sequence seen per aggregate id is the sequence that was committed.
     *
     * @param limit batch size
     * @return the oldest unpublished events, locked for this transaction
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextUnpublished(@Param("limit") int limit);

    /**
     * @param cutoff drop rows published before this instant
     * @return how many rows were removed
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt IS NOT NULL AND e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * @param aggregateId aggregate id, e.g. an order id
     * @return every event written for that aggregate, oldest first
     */
    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);

    /** @return the rows still waiting to be published, oldest first */
    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc();
}
