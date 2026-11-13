package com.example.orders.support.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Same accept-or-replace rule as the gateway; the services must not diverge from it. */
class CorrelationIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "abc-123", "ABCdef-0123456789", "0f8fad5b-d9cb-469f-a165-70867728950e"})
    void keepsSaneValues(String value) {
        assertThat(CorrelationId.isSane(value)).isTrue();
        assertThat(CorrelationId.sanitise(value)).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "has space", "has\nnewline", "has:colon", "über", "a/b", "\t"})
    void replacesUnsafeValues(String value) {
        assertThat(CorrelationId.isSane(value)).isFalse();
        String sanitised = CorrelationId.sanitise(value);
        assertThat(UUID.fromString(sanitised)).isNotNull();
    }

    @Test
    void replacesOverlongValues() {
        String tooLong = "a".repeat(65);
        assertThat(CorrelationId.isSane(tooLong)).isFalse();
        assertThat(CorrelationId.sanitise(tooLong)).isNotEqualTo(tooLong);
        assertThat(CorrelationId.isSane("a".repeat(64))).isTrue();
    }
}
