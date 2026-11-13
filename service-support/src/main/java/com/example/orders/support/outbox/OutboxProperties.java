package com.example.orders.support.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the shared transactional outbox, under the {@code outbox.*} prefix.
 *
 * <p>Every service that produces events switches the machinery on with {@code outbox.enabled=true}
 * and owns its own {@code outbox_events} table through its own Flyway migration.
 */
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    /** Whether this service uses the outbox at all. */
    private boolean enabled;

    /** {@code aggregate_type} written by {@code OutboxWriter.record(...)} without an explicit one. */
    private String aggregateType = OutboxEvent.DEFAULT_AGGREGATE_TYPE;

    /**
     * How often the relay looks for events to publish. Short, because this is the latency between
     * a committed state change and the rest of the system hearing about it.
     */
    private long pollIntervalMs = 500;

    /**
     * Rows claimed per poll with {@code FOR UPDATE SKIP LOCKED}. The locks are held for the whole
     * batch, so the batch stays small enough that a stuck broker cannot block other instances for
     * long.
     */
    private int batchSize = 50;

    /**
     * How long the relay waits for one send result. Larger than {@code producer.deliveryTimeoutMs},
     * so the producer always resolves the future itself instead of us giving up on a record still
     * in flight (which would risk a duplicate on the topic).
     */
    private Duration sendTimeout = Duration.ofSeconds(15);

    /**
     * Published rows are the audit trail of what this service announced; kept this long, then
     * dropped so the queue table does not turn into an archive.
     */
    private Duration retention = Duration.ofDays(7);

    /** How often the retention cleanup runs. */
    private long cleanupIntervalMs = 3_600_000;

    /** Turned off in tests that drive the relay by hand. */
    private boolean schedulerEnabled = true;

    private final Producer producer = new Producer();

    /** Timeouts of the relay's own Kafka producer. */
    public static class Producer {

        /** How long {@code send()} may block waiting for cluster metadata. */
        private int maxBlockMs = 5000;

        /** How long one produce request may take. */
        private int requestTimeoutMs = 5000;

        /** Total budget for a record including the client's internal retries. */
        private int deliveryTimeoutMs = 10000;

        public int getMaxBlockMs() {
            return maxBlockMs;
        }

        public void setMaxBlockMs(int maxBlockMs) {
            this.maxBlockMs = maxBlockMs;
        }

        public int getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(int requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public int getDeliveryTimeoutMs() {
            return deliveryTimeoutMs;
        }

        public void setDeliveryTimeoutMs(int deliveryTimeoutMs) {
            this.deliveryTimeoutMs = deliveryTimeoutMs;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public Producer getProducer() {
        return producer;
    }
}
