package com.example.orders.order.domain;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Orders by id, by owner and — for administrators — across all owners. */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * @param id order id
     * @return the order with its lines already loaded, so the response can be built outside the
     *         session ({@code open-in-view} is off)
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    /**
     * The idempotent-replay lookup. Scoped by user on purpose: an {@code Idempotency-Key} is the
     * client's own bookkeeping, so two customers may pick the same string without colliding.
     *
     * @param userId         owner
     * @param idempotencyKey key the client sent
     * @return the order that key already produced, if any
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    // The paged finders deliberately do NOT join-fetch the lines: a collection fetch plus
    // LIMIT/OFFSET forces Hibernate to paginate in memory. The lines are loaded in batches
    // (hibernate.default_batch_fetch_size) while the read-only transaction is still open.

    /**
     * @param userId   owner
     * @param pageable page and sort
     * @return that user's orders
     */
    Page<Order> findByUserId(UUID userId, Pageable pageable);

    /**
     * @param status   status to filter on
     * @param pageable page and sort
     * @return all orders in that status, regardless of owner
     */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * The saga-timeout sweeper's query: orders whose last transition is older than the deadline and
     * that the saga has still not finished. Capped, so one sweep cannot try to compensate a backlog
     * of thousands of orders inside one scheduler tick.
     *
     * @param statuses non-final states to look at, i.e. {@link OrderStatus#UNFINISHED}
     * @param cutoff   the deadline; rows last touched before it are considered stuck
     * @return at most 50 stuck orders, the longest-stuck first
     */
    List<Order> findTop50ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<OrderStatus> statuses, OffsetDateTime cutoff);
}
