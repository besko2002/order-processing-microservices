package com.example.orders.gateway.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accept-or-replace policy for the {@code X-Correlation-Id} header.
 *
 * <p>The id ends up in log files, in downstream headers and later in Kafka headers, so a
 * client-supplied value is only reused when it cannot be used to forge log lines or smuggle
 * header content: at most 64 characters, letters, digits and hyphens only. Anything else —
 * empty, over-long, containing a newline, a space or punctuation — is silently replaced with a
 * fresh UUID rather than rejected, because a bad correlation id is never a reason to fail a
 * business request.
 */
public final class CorrelationId {

    private static final Pattern SANE = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private CorrelationId() {
    }

    /**
     * @param candidate value the client sent, may be {@code null}
     * @return {@code candidate} when it is sane, otherwise a newly generated UUID
     */
    public static String sanitise(String candidate) {
        return isSane(candidate) ? candidate : UUID.randomUUID().toString();
    }

    /**
     * @param candidate value the client sent, may be {@code null}
     * @return whether the value may be propagated as-is
     */
    public static boolean isSane(String candidate) {
        return candidate != null && SANE.matcher(candidate).matches();
    }
}
