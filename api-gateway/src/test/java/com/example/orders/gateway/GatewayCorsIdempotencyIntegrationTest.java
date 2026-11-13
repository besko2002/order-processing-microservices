package com.example.orders.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.gateway.security.GatewayHeaders;
import java.time.Duration;
import java.util.UUID;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Regression for Priority 3: <b>the gateway must let the {@code Idempotency-Key} header through
 * CORS</b>.
 *
 * <p>{@code POST /api/orders} honours an optional {@code Idempotency-Key} replay guard, but that
 * guard is defined by order-service. For a browser client the gateway is the edge that decides
 * which request headers a pre-flight may name — if the header is not on the gateway's CORS list,
 * the pre-flight is refused and the browser client cannot retry a checkout safely, even though
 * the gateway's CORS rules were never meant to forbid it.
 *
 * <p>What these tests pin, all at the real port with a proxied downstream:
 * <ul>
 *   <li>a pre-flight may name {@code Idempotency-Key} (with the other client headers) and is
 *       answered;</li>
 *   <li>the answer never becomes a wildcard origin, because credentials are allowed — the
 *       origin stays the exact configured one, and unchanged rules (defaults and other origins)
 *       keep behaving exactly as before;</li>
 *   <li>the actual checkout then reaches order-service with the replay guard intact.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "RATE_LIMIT_REPLENISH_RATE=1000",
                "RATE_LIMIT_BURST_CAPACITY=2000"
        })
class GatewayCorsIdempotencyIntegrationTest extends GatewayTestEnvironment {

    @LocalServerPort
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ---------------------------------------------------------------- pre-flight accepts the guard

    @Test
    void aPreflightMayAskForTheIdempotencyKeyHeader() {
        client().options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,Idempotency-Key")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        allowed -> assertThat(allowed.toLowerCase())
                                .contains("idempotency-key", "authorization", "content-type"))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        allowed -> assertThat(allowed.toLowerCase()).contains("post"))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                        allowed -> assertThat(allowed).isEqualTo("true"));
    }

    @Test
    void theAllowedPreflightNeverAnswersWithAWildcardOrigin() {
        // allowCredentials is on, so any wildcard origin would be a CORS violation (and an
        // any-origin door on a credentialed API). The answer must name the exact origin.
        client().options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "idempotency-key")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, value -> {
                    assertThat(value)
                            .isEqualTo(ALLOWED_ORIGIN)
                            .isNotEqualTo("*");
                });
    }

    // ---------------------------------------------------------------- unchanged rejection rules

    @Test
    void aPreflightFromAnotherOriginIsStillRejectedEvenWithTheGuardHeader() {
        client().options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,Idempotency-Key")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
    }

    @Test
    void aPreflightNamingAnUnlistedHeaderNeverAdmitsThatHeader() {
        // The new rule admits the replay guard specifically; it must not admit arbitrary headers.
        // Spring's CORS processor answers a pre-flight that names an unlisted header with the
        // allowed subset of what was named (DefaultCorsProcessor/CorsConfiguration in the bundled
        // Spring Framework), so the honest check is on the answer itself: the unlisted name must
        // not appear in Access-Control-Allow-Headers. That header is what a browser treats as
        // permission for the actual request — a request header absent from it is stopped by the
        // user agent, which is the same safety property a hard 403 would give. The forbidden
        // regression this pins is a wildcard header list sneaking in: allowAll would echo
        // X-Custom-Forage straight to the client.
        client().options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "idempotency-key,X-Custom-Forage")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        value -> assertThat(value)
                                .isEqualTo(ALLOWED_ORIGIN)
                                .isNotEqualTo("*"))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        allowed -> assertThat(allowed.toLowerCase())
                                .contains("idempotency-key")
                                .doesNotContain("custom-forage"));
    }

    @Test
    void aPreflightNamingOnlyUnlistedHeadersIsStillRejected() {
        // Complement of the subset rule above: when nothing the pre-flight names is on the allow
        // list, the processor refuses it outright instead of answering with an empty permission.
        client().options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Custom-Forage,X-Evil")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    // ---------------------------------------------------------------- the actual request

    @Test
    void theIdempotentCheckoutReachesOrderServiceWithItsReplayGuard() throws InterruptedException {
        String idempotencyKey = UUID.randomUUID().toString();

        client().post().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(newUserId(), "USER"))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .bodyValue("{\"items\":[]}")
                .exchange()
                .expectStatus().isOk()
                // Same exact-origin rule applies to the answer of a credentialed request.
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN);

        RecordedRequest proxied = takeProxiedRequest();
        assertThat(proxied.getPath()).isEqualTo("/api/orders");
        assertThat(proxied.getHeader("Idempotency-Key")).isEqualTo(idempotencyKey);
        // Correlation stays what it is on every routed call.
        assertThat(proxied.getHeader(GatewayHeaders.CORRELATION_ID)).isNotBlank();
    }
}
