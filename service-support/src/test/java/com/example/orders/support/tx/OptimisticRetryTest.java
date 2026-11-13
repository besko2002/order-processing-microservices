package com.example.orders.support.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Retry on a lost race, but bounded: a hot row must not turn into a livelock. */
class OptimisticRetryTest {

    @Test
    void returnsTheResultOfTheFirstSuccessfulAttempt() {
        AtomicInteger calls = new AtomicInteger();

        String result = OptimisticRetry.execute(3, () -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
    }

    @Test
    void retriesUntilAnAttemptCommits() {
        AtomicInteger calls = new AtomicInteger();

        String result = OptimisticRetry.execute(3, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new ObjectOptimisticLockingFailureException("Product", 1L);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterTheConfiguredNumberOfAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> OptimisticRetry.execute(3, () -> {
            calls.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("Product", 1L);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(calls).hasValue(3);
    }

    @Test
    void runsAtLeastOnceEvenWithANonsenseAttemptCount() {
        AtomicInteger calls = new AtomicInteger();

        OptimisticRetry.execute(0, calls::incrementAndGet);

        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotRetryOtherFailures() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> OptimisticRetry.execute(3, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("not a locking problem");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(calls).hasValue(1);
    }
}
