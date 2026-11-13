package com.example.orders.support.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accept-or-replace policy for the {@code X-Correlation-Id} header — the exact same rule the
 * gateway applies, repeated here because a service may also be called directly (and because the
 * gateway runs on WebFlux and cannot share this module's servlet code).
 *
 * <p>The id ends up in log files and later in Kafka headers, so a caller-supplied value is only
 * reused when it cannot be used to forge log lines or smuggle header content: at most 64
 * characters, letters, digits and hyphens only. Anything else is silently replaced with a fresh
 * UUID rather than rejected, because a bad correlation id is never a reason to fail a request.
 */
public final class CorrelationId {

    /** Request and response header carrying the id. */
    public static final String HEADER = "X-Correlation-Id";

    /** SLF4J MDC key, referenced by the log patterns as {@code %X{correlationId}}. */
    public static final String MDC_KEY = "correlationId";

    private static final Pattern SANE = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private CorrelationId() {
    }

    /**
     * @param candidate value the caller sent, may be {@code null}
     * @return {@code candidate} when it is sane, otherwise a newly generated UUID
     */
    public static String sanitise(String candidate) {
        return isSane(candidate) ? candidate : UUID.randomUUID().toString();
    }

    /**
     * @param candidate value the caller sent, may be {@code null}
     * @return whether the value may be propagated as-is
     */
    public static boolean isSane(String candidate) {
        return candidate != null && SANE.matcher(candidate).matches();
    }
}
