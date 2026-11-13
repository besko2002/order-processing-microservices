package com.example.orders.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderEventResponseTest {

    @Test
    void theTraceIdIsTheMiddleSegmentOfAWellFormedTraceparent() {
        assertThat(OrderEventResponse.traceIdOf(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "garbage",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-01",                  // too few segments
            "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", // unknown version
            "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01", // upper-case trace id
            "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01",  // 31 trace-id digits
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01", // all-zero trace id
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00F067aa0ba902b7-01", // upper-case parent id
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b-01",  // 15 parent-id digits
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01", // all-zero parent id
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0A", // upper-case flags
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0g", // non-hex flags
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1",  // one flag digit
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01suffix",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 ",
            " 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    })
    void aMissingOrMalformedTraceparentYieldsNoTraceId(String traceparent) {
        assertThat(OrderEventResponse.traceIdOf(traceparent)).isNull();
    }
}
