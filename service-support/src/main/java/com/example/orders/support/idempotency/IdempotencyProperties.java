package com.example.orders.support.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings of the idempotent-consumer helper, under the {@code idempotency.*} prefix. */
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    /** Whether this service consumes events and therefore needs a de-duplication log. */
    private boolean enabled;

    /**
     * How long a "handled" note is kept. Well beyond any topic retention or replay window: after
     * this, a redelivery of the same event id would be treated as new work.
     */
    private Duration retention = Duration.ofDays(7);

    /** How often the retention sweep runs. */
    private long cleanupIntervalMs = 3_600_000;

    /** Turned off in tests that want no background writes. */
    private boolean schedulerEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
}
