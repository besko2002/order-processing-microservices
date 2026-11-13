package com.example.orders.support.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Retry and dead-letter settings of the shared event listeners, under {@code messaging.consumer.*}. */
@ConfigurationProperties(prefix = "messaging.consumer")
public class EventConsumerProperties {

    /** Whether this service listens to Kafka. */
    private boolean enabled;

    /**
     * Listener threads per container. One by default: the message key is the order id, so a single
     * thread per partition already gives per-order ordering, and more threads only help when there
     * are more partitions than instances.
     */
    private int concurrency = 1;

    /**
     * Redeliveries inside the consumer before the record is dead-lettered. Kept small on purpose —
     * a record that fails four times needs a human, not a longer loop.
     */
    private int retries = 3;

    /** Wait before the first retry. */
    private long initialBackoffMs = 500;

    /** Factor applied to the wait after every failed retry. */
    private double backoffMultiplier = 2.0;

    /** Ceiling for the wait, so the partition is never parked for minutes. */
    private long maxBackoffMs = 4000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        this.initialBackoffMs = initialBackoffMs;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
        this.maxBackoffMs = maxBackoffMs;
    }
}
