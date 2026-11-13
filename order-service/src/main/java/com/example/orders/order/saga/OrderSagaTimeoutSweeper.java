package com.example.orders.order.saga;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.domain.OrderStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The saga's deadline.
 *
 * <p>An orchestrated saga has one failure mode no amount of retrying fixes: a step that never
 * answers. inventory-service may be down while the order is {@code PENDING}, a
 * {@code ProcessPayment} may have been dead-lettered, a service may have been redeployed mid-flow.
 * Without a deadline the order would sit in a non-final state forever — and worse, keep its stock
 * reserved — so after {@code ORDER_SAGA_TIMEOUT} the sweeper cancels it <em>with</em> compensation.
 *
 * <p>The timeout is measured from the last transition, not from creation: an order that is making
 * progress is not stuck. It is generous compared to the saga's normal latency (which is
 * milliseconds), because cancelling an order that was merely slow is itself a failure.
 */
@Component
@ConditionalOnProperty(name = "orders.saga.timeout-sweeper-enabled", havingValue = "true", matchIfMissing = true)
class OrderSagaTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaTimeoutSweeper.class);

    private final OrderRepository orders;
    private final OrderSaga saga;
    private final Duration timeout;

    OrderSagaTimeoutSweeper(
            OrderRepository orders,
            OrderSaga saga,
            @Value("${orders.saga.timeout:PT2M}") Duration timeout) {
        this.orders = orders;
        this.saga = saga;
        this.timeout = timeout;
    }

    @Scheduled(
            fixedDelayString = "${orders.saga.sweep-interval-ms:15000}",
            initialDelayString = "${orders.saga.sweep-interval-ms:15000}")
    void sweep() {
        cancelTimedOut();
    }

    /**
     * Cancels every order that has been stuck for longer than the timeout.
     *
     * <p>The scan is read-only and each cancellation is its own transaction, so a sweep never holds
     * locks across orders and a failure on one order does not roll back the others.
     *
     * @return how many orders this run cancelled
     */
    @Transactional(readOnly = true)
    public int cancelTimedOut() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(timeout);
        List<Order> stuck = orders.findTop50ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                OrderStatus.UNFINISHED, cutoff);
        if (stuck.isEmpty()) {
            return 0;
        }
        log.warn("Saga timeout sweep found {} order(s) with no progress since {}", stuck.size(), cutoff);
        int cancelled = 0;
        for (Order order : stuck) {
            String reason = "The order was cancelled because step '%s' did not complete within %s"
                    .formatted(order.getStatus(), timeout);
            if (saga.timeOut(order.getId(), reason)) {
                cancelled++;
            }
        }
        return cancelled;
    }
}
