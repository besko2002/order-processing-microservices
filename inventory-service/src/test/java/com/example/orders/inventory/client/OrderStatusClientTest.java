package com.example.orders.inventory.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The fail-closed contract of the order-service lookup, against a mock HTTP server (no Spring
 * context, no containers): only a well-formed {@code 200} naming the requested order with the exact
 * status {@code CANCELLED} or {@code REJECTED} permits a release; everything else — every other
 * status, every malformed, foreign, oversized or missing answer, every error code, timeout,
 * refused connection and redirect — does not.
 */
class OrderStatusClientTest {

    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.secret-payload.secret-signature";

    private final UUID orderId = UUID.randomUUID();

    private MockWebServer server;
    private OrderStatusClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        client = clientTo(server, 1000, 400);
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    // ------------------------------------------------------------------ the only two yeses

    @Test
    void aCancelledOrderPermitsReleaseAndTheCallCarriesTheCallersToken() throws Exception {
        server.enqueue(json(200, order(orderId, "CANCELLED")));

        OrderStatusVerdict verdict = client.check(orderId, TOKEN);

        assertThat(verdict).isEqualTo(OrderStatusVerdict.CANCELLED);
        assertThat(verdict.permitsRelease()).isTrue();
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/api/orders/" + orderId);
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(request.getHeader("X-Correlation-Id")).isNotBlank();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void aRejectedOrderPermitsRelease() {
        server.enqueue(json(200, order(orderId, "REJECTED")));

        assertThat(client.check(orderId, TOKEN)).isEqualTo(OrderStatusVerdict.REJECTED);
        assertThat(OrderStatusVerdict.REJECTED.permitsRelease()).isTrue();
    }

    @Test
    void aFullOrderBodyWithUnrelatedFieldsIsAccepted() {
        server.enqueue(json(200, """
                {"id":"%s","orderNumber":"ORD-1","userId":"%s","status":"CANCELLED","totalAmount":12.50,
                 "currency":"EUR","failureReason":"Cancelled by the customer","items":[{"sku":"A","quantity":1}]}
                """.formatted(orderId, UUID.randomUUID())));

        assertThat(client.check(orderId, TOKEN)).isEqualTo(OrderStatusVerdict.CANCELLED);
    }

    // ------------------------------------------------------------------ every other state refuses

    @Test
    void everyNonTerminalStatusIsActiveAndRefused() {
        for (String status : List.of(
                "PENDING", "STOCK_RESERVED", "PAYMENT_PENDING", "CONFIRMED", "CANCELLATION_PENDING")) {
            server.enqueue(json(200, order(orderId, status)));

            OrderStatusVerdict verdict = client.check(orderId, TOKEN);

            assertThat(verdict).as(status).isEqualTo(OrderStatusVerdict.ACTIVE);
            assertThat(verdict.permitsRelease()).as(status).isFalse();
        }
    }

    @Test
    void unknownMissingOrMistypedStatusesAreUnverifiable() {
        List<String> bodies = List.of(
                order(orderId, "cancelled"),
                order(orderId, "Cancelled"),
                order(orderId, "CANCELLED "),
                order(orderId, "ARCHIVED"),
                order(orderId, ""),
                "{\"id\":\"%s\",\"status\":null}".formatted(orderId),
                "{\"id\":\"%s\",\"status\":7}".formatted(orderId),
                "{\"id\":\"%s\",\"status\":[\"CANCELLED\"]}".formatted(orderId),
                "{\"id\":\"%s\",\"status\":{\"name\":\"CANCELLED\"}}".formatted(orderId),
                "{\"id\":\"%s\"}".formatted(orderId));
        for (String body : bodies) {
            server.enqueue(json(200, body));

            assertThat(client.check(orderId, TOKEN)).as(body).isEqualTo(OrderStatusVerdict.UNVERIFIABLE);
        }
    }

    @Test
    void anAnswerAboutAnotherOrderOrNoOrderIsUnverifiable() {
        List<String> bodies = List.of(
                order(UUID.randomUUID(), "CANCELLED"),
                "{\"status\":\"CANCELLED\"}",
                "{\"id\":null,\"status\":\"CANCELLED\"}",
                "{\"id\":\"not-a-uuid\",\"status\":\"CANCELLED\"}",
                "{\"id\":42,\"status\":\"CANCELLED\"}");
        for (String body : bodies) {
            server.enqueue(json(200, body));

            assertThat(client.check(orderId, TOKEN)).as(body).isEqualTo(OrderStatusVerdict.UNVERIFIABLE);
        }
    }

    @Test
    void malformedEmptyAndNonObjectBodiesAreUnverifiable() {
        for (String body : List.of("{", "not json", "", "[]", "[{\"id\":\"x\"}]", "\"CANCELLED\"", "null", "42")) {
            server.enqueue(json(200, body));

            assertThat(client.check(orderId, TOKEN)).as("body <%s>", body).isEqualTo(OrderStatusVerdict.UNVERIFIABLE);
        }
    }

    @Test
    void anOversizedBodyIsUnverifiableEvenWhenItParses() {
        String padding = "x".repeat(OrderStatusClient.MAX_BODY_BYTES);
        server.enqueue(json(200,
                "{\"id\":\"%s\",\"status\":\"CANCELLED\",\"padding\":\"%s\"}".formatted(orderId, padding)));

        assertThat(client.check(orderId, TOKEN)).isEqualTo(OrderStatusVerdict.UNVERIFIABLE);
    }

    // ------------------------------------------------------------------ error answers

    @Test
    void notFoundDeniedAndServerErrorsAreTheirOwnRefusals() {
        assertThat(answeredWith(404)).isEqualTo(OrderStatusVerdict.NOT_FOUND);
        assertThat(answeredWith(401)).isEqualTo(OrderStatusVerdict.ACCESS_DENIED);
        assertThat(answeredWith(403)).isEqualTo(OrderStatusVerdict.ACCESS_DENIED);
        for (int code : List.of(500, 502, 503, 504, 408, 429)) {
            assertThat(answeredWith(code)).as("HTTP %d", code).isEqualTo(OrderStatusVerdict.UNAVAILABLE);
        }
        for (int code : List.of(400, 409, 410, 204, 201)) {
            assertThat(answeredWith(code)).as("HTTP %d", code).isEqualTo(OrderStatusVerdict.UNVERIFIABLE);
        }
    }

    @Test
    void aRedirectIsNeverFollowedSoTheTokenCannotTravelElsewhere() throws Exception {
        MockWebServer elsewhere = new MockWebServer();
        elsewhere.start();
        try {
            elsewhere.enqueue(json(200, order(orderId, "CANCELLED")));
            server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", elsewhere.url("/api/orders/" + orderId).toString()));
            server.enqueue(json(200, order(orderId, "CANCELLED")));

            assertThat(client.check(orderId, TOKEN)).isEqualTo(OrderStatusVerdict.UNVERIFIABLE);

            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(elsewhere.getRequestCount()).isZero();
        }
        finally {
            elsewhere.shutdown();
        }
    }

    // ------------------------------------------------------------------ transport failures

    @Test
    void aServerThatNeverAnswersTimesOutAndIsUnavailable() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        long started = System.nanoTime();

        OrderStatusVerdict verdict = client.check(orderId, TOKEN);

        assertThat(verdict).isEqualTo(OrderStatusVerdict.UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aBodyThatStallsAfterTheHeadersTimesOutToo() {
        server.enqueue(json(200, order(orderId, "CANCELLED")).setBodyDelay(5, TimeUnit.SECONDS));
        long started = System.nanoTime();

        OrderStatusVerdict verdict = client.check(orderId, TOKEN);

        assertThat(verdict).isEqualTo(OrderStatusVerdict.UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void aRefusedConnectionAndAHangUpAreUnavailable() throws IOException {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        assertThat(client.check(orderId, TOKEN)).isEqualTo(OrderStatusVerdict.UNAVAILABLE);

        // A port nothing listens on any more: the connection is refused.
        MockWebServer gone = new MockWebServer();
        gone.start();
        OrderStatusClient toNowhere = clientTo(gone, 1000, 400);
        gone.shutdown();
        assertThat(toNowhere.check(orderId, TOKEN)).isEqualTo(OrderStatusVerdict.UNAVAILABLE);
    }

    // ------------------------------------------------------------------ guards and secrecy

    @Test
    void aMissingOrderIdOrTokenIsAProgrammingErrorNotARemoteVerdict() {
        assertThatThrownBy(() -> client.check(null, TOKEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.check(orderId, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.check(orderId, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void theBearerTokenNeverReachesTheLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            server.enqueue(json(200, order(orderId, "CANCELLED")));
            server.enqueue(json(200, "not json"));
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(new MockResponse().setResponseCode(403));
            server.enqueue(new MockResponse().setResponseCode(404));
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            for (int i = 0; i < 6; i++) {
                client.check(orderId, TOKEN);
            }
        }
        finally {
            root.detachAppender(logs);
        }

        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            String rendered = event.getFormattedMessage() + " " + event.getThrowableProxy();
            assertThat(rendered).doesNotContain(TOKEN).doesNotContain("secret-payload").doesNotContainIgnoringCase("bearer");
        }
    }

    // ------------------------------------------------------------------ helpers

    private OrderStatusVerdict answeredWith(int status) {
        server.enqueue(new MockResponse().setResponseCode(status));
        return client.check(orderId, TOKEN);
    }

    private static OrderStatusClient clientTo(MockWebServer server, long connectMs, long readMs) {
        return new OrderStatusClient(
                OrderClientConfiguration.create("http://localhost:" + server.getPort(), connectMs, readMs),
                new ObjectMapper());
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static String order(UUID id, String status) {
        return "{\"id\":\"%s\",\"status\":\"%s\"}".formatted(id, status);
    }
}
