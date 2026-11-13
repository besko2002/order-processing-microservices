package com.example.orders.support.tx;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Runs a transactional unit of work again when it lost an optimistic-locking race.
 *
 * <p>Bounded on purpose: a conflict means another transaction committed first, so replaying the
 * work on fresh data is correct, but an unbounded loop would turn a hot row into a livelock. After
 * the last attempt the failure is rethrown and becomes a {@code 409} for the caller.
 *
 * <p>The work must run in its own transaction, so the supplier has to call <em>another</em> bean's
 * {@code @Transactional} method — a self-invocation would not start a new transaction and the retry
 * would replay inside the already-doomed one.
 */
public final class OptimisticRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticRetry.class);

    private OptimisticRetry() {
    }

    /**
     * @param attempts how often the work may run in total, at least 1
     * @param work     the transactional call
     * @param <T>      result type
     * @return the result of the first attempt that commits
     * @throws ObjectOptimisticLockingFailureException when every attempt lost the race
     */
    public static <T> T execute(int attempts, Supplier<T> work) {
        int total = Math.max(1, attempts);
        ObjectOptimisticLockingFailureException last = null;
        for (int attempt = 1; attempt <= total; attempt++) {
            try {
                return work.get();
            }
            catch (ObjectOptimisticLockingFailureException e) {
                last = e;
                log.debug("Optimistic locking conflict on attempt {} of {}", attempt, total);
            }
        }
        throw last;
    }
}
