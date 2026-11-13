package com.example.orders.support.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The filter has three jobs: sanitise, publish to the MDC, echo back. */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void reusesASaneIncomingId() throws Exception {
        String incoming = "abc-123-DEF";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(CorrelationId.HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(seenInMdc));

        assertThat(seenInMdc.get()).isEqualTo(incoming);
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(incoming);
    }

    @Test
    void generatesAnIdWhenTheHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(seenInMdc));

        String generated = response.getHeader(CorrelationId.HEADER);
        assertThat(generated).isNotNull();
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(seenInMdc.get()).isEqualTo(generated);
    }

    @Test
    void replacesAnIdThatCouldForgeLogLines() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(CorrelationId.HEADER, "bad id\nINFO forged log line");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(seenInMdc));

        assertThat(seenInMdc.get()).doesNotContain("forged").doesNotContain("\n");
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(seenInMdc.get());
    }

    @Test
    void echoesExactlyOneHeaderValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(CorrelationId.HEADER, "only-one");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // A downstream handler that already echoed the header must not produce a duplicate.
        response.setHeader(CorrelationId.HEADER, "stale");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeaders(CorrelationId.HEADER)).containsExactly("only-one");
    }

    @Test
    void clearsTheMdcAfterwardsSoPooledThreadsStayClean() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(CorrelationId.HEADER, "leak-check");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void clearsTheMdcEvenWhenTheRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("handler blew up");
            });
        }
        catch (Exception expected) {
            // the filter must not swallow it
        }

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
        assertThat(response.getHeader(CorrelationId.HEADER)).isNotNull();
    }

    private static MockFilterChain capturingChain(AtomicReference<String> seenInMdc) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                seenInMdc.set(MDC.get(CorrelationId.MDC_KEY));
            }
        };
    }
}
