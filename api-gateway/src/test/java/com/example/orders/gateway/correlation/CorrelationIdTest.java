package com.example.orders.gateway.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The accept-or-replace rule for a client supplied {@code X-Correlation-Id}. */
class CorrelationIdTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "abc",
            "trace-0123456789-abcdef",
            "0c2f6b5e-4f0f-4b63-8d8f-1b6b4f2f1f77",
            "A1B2C3"
    })
    void acceptsSaneValues(String candidate) {
        assertThat(CorrelationId.isSane(candidate)).isTrue();
        assertThat(CorrelationId.sanitise(candidate)).isEqualTo(candidate);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "trace id",
            "trace\nid",
            "trace\r\nSet-Cookie: admin=true",
            "trace:id",
            "../../etc/passwd",
            "<script>alert(1)</script>"
    })
    void replacesAnythingElse(String candidate) {
        assertThat(CorrelationId.isSane(candidate)).isFalse();

        String sanitised = CorrelationId.sanitise(candidate);
        assertThat(sanitised).isNotEqualTo(candidate);
        assertThat(CorrelationId.isSane(sanitised)).isTrue();
        assertThat(UUID.fromString(sanitised)).isNotNull();
    }

    @Test
    void replacesOverlongValues() {
        String candidate = "a".repeat(65);

        assertThat(CorrelationId.isSane(candidate)).isFalse();
        assertThat(CorrelationId.sanitise(candidate)).hasSize(36);
    }

    @Test
    void acceptsExactlySixtyFourCharacters() {
        String candidate = "a".repeat(64);

        assertThat(CorrelationId.sanitise(candidate)).isEqualTo(candidate);
    }
}
