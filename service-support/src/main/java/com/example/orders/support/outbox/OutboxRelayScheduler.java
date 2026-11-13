package com.example.orders.support.outbox;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * Ticks the relay. Kept apart from {@link OutboxRelay} so that the relay itself stays a plain,
 * directly callable bean — a test (and, later, an operator endpoint) can drain the outbox on
 * demand without waiting for a timer.
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}: the next poll starts after the previous one
 * finished, so a slow broker makes the relay poll less often rather than pile runs on top of each
 * other.
 */
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    /**
     * @param relay the relay this scheduler drives
     */
    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(
            fixedDelayString = "${outbox.poll-interval-ms:500}",
            initialDelayString = "${outbox.poll-interval-ms:500}")
    void poll() {
        relay.publishPending();
    }

    @Scheduled(
            fixedDelayString = "${outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${outbox.cleanup-interval-ms:3600000}")
    void cleanUp() {
        relay.purgePublished();
    }
}
