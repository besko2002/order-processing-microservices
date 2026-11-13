package com.example.orders.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.gateway.security.GatewayHeaders;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * End-to-end behaviour of the edge: who gets through without a token, who gets 401/403, which
 * identity headers reach the downstream service, and how the correlation id and CORS rules
 * behave. Rate limits are set deliberately high here so they never interfere; the 429 path has
 * its own test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "RATE_LIMIT_REPLENISH_RATE=1000",
                "RATE_LIMIT_BURST_CAPACITY=2000"
        })
class GatewayEdgeIntegrationTest extends GatewayTestEnvironment {

    @LocalServerPort
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ---------------------------------------------------------------- public routes

    @Test
    void registerPassesThroughWithoutAToken() throws InterruptedException {
        client().post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@b.c\",\"password\":\"Secret@12345\",\"name\":\"A\"}")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest proxied = takeProxiedRequest();
        assertThat(proxied).isNotNull();
        assertThat(proxied.getPath()).isEqualTo("/api/auth/register");
    }

    @Test
    void loginPassesThroughWithoutAToken() throws InterruptedException {
        client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@b.c\",\"password\":\"Secret@12345\"}")
                .exchange()
                .expectStatus().isOk();

        assertThat(takeProxiedRequest().getPath()).isEqualTo("/api/auth/login");
    }

    @Test
    void publicCatalogGetPassesThroughWithoutAToken() throws InterruptedException {
        client().get().uri("/api/products/42")
                .exchange()
                .expectStatus().isOk();

        assertThat(takeProxiedRequest().getPath()).isEqualTo("/api/products/42");
    }

    @Test
    void actuatorHealthIsPublic() {
        client().get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    // ---------------------------------------------------------------- authentication

    @Test
    void protectedRouteWithoutATokenIsRejectedWithTheSharedErrorBody() {
        client().get().uri("/api/orders/x")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized")
                .jsonPath("$.message").isNotEmpty()
                .jsonPath("$.path").isEqualTo("/api/orders/x")
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void validTokenReachesTheServiceWithAuthorizationAndIdentityHeaders() throws InterruptedException {
        String userId = newUserId();

        client().get().uri("/api/orders/x")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(userId, "USER"))
                .exchange()
                .expectStatus().isOk();

        RecordedRequest proxied = takeProxiedRequest();
        assertThat(proxied.getHeader(HttpHeaders.AUTHORIZATION)).startsWith("Bearer ");
        assertThat(proxied.getHeader(GatewayHeaders.USER_ID)).isEqualTo(userId);
        assertThat(proxied.getHeader(GatewayHeaders.USER_ROLES)).isEqualTo("USER");
    }

    @Test
    void clientSuppliedIdentityHeadersAreOverwrittenForAuthenticatedCalls() throws InterruptedException {
        String userId = newUserId();

        client().get().uri("/api/orders/x")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(userId, "USER"))
                .header(GatewayHeaders.USER_ID, "00000000-0000-0000-0000-00000000dead")
                .header(GatewayHeaders.USER_ROLES, "ADMIN")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest proxied = takeProxiedRequest();
        assertThat(proxied.getHeader(GatewayHeaders.USER_ID)).isEqualTo(userId);
        assertThat(proxied.getHeader(GatewayHeaders.USER_ROLES)).isEqualTo("USER");
    }

    @Test
    void clientSuppliedIdentityHeadersAreStrippedOnPublicRoutes() throws InterruptedException {
        client().get().uri("/api/products/42")
                .header(GatewayHeaders.USER_ID, "00000000-0000-0000-0000-00000000dead")
                .header(GatewayHeaders.USER_ROLES, "ADMIN")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest proxied = takeProxiedRequest();
        assertThat(proxied.getHeader(GatewayHeaders.USER_ID)).isNull();
        assertThat(proxied.getHeader(GatewayHeaders.USER_ROLES)).isNull();
    }

    @Test
    void expiredTokenIsRejected() {
        expectUnauthorized(expiredToken(newUserId()));
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() {
        expectUnauthorized(tokenSignedByAnotherKey(newUserId()));
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        expectUnauthorized(tokenFromAnotherIssuer(newUserId()));
    }

    private void expectUnauthorized(String token) {
        client().get().uri("/api/orders/x")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    // ---------------------------------------------------------------- authorisation

    @Test
    void userIsForbiddenOnStockAdministration() {
        client().get().uri("/api/inventory/x")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(newUserId(), "USER"))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.error").isEqualTo("Forbidden")
                .jsonPath("$.path").isEqualTo("/api/inventory/x");
    }

    @Test
    void adminReachesStockAdministration() throws InterruptedException {
        String adminId = newUserId();

        client().get().uri("/api/inventory/x")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(adminId, "ADMIN"))
                .exchange()
                .expectStatus().isOk();

        RecordedRequest proxied = takeProxiedRequest();
        assertThat(proxied.getPath()).isEqualTo("/api/inventory/x");
        assertThat(proxied.getHeader(GatewayHeaders.USER_ID)).isEqualTo(adminId);
        assertThat(proxied.getHeader(GatewayHeaders.USER_ROLES)).isEqualTo("ADMIN");
    }

    @Test
    void gatewayActuatorIsAdminOnly() {
        client().get().uri("/actuator/gateway/routes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(newUserId(), "USER"))
                .exchange()
                .expectStatus().isForbidden();

        client().get().uri("/actuator/gateway/routes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(newUserId(), "ADMIN"))
                .exchange()
                .expectStatus().isOk();
    }

    // ---------------------------------------------------------------- correlation id

    @Test
    void correlationIdIsGeneratedWhenTheClientSendsNone() throws InterruptedException {
        String echoed = client().get().uri("/api/products/1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayHeaders.CORRELATION_ID)
                .expectBody(String.class)
                .returnResult()
                .getResponseHeaders()
                .getFirst(GatewayHeaders.CORRELATION_ID);

        assertThat(echoed).isNotBlank();
        assertThat(takeProxiedRequest().getHeader(GatewayHeaders.CORRELATION_ID)).isEqualTo(echoed);
    }

    @Test
    void saneCorrelationIdIsPreserved() throws InterruptedException {
        String supplied = "trace-0123456789-abcdef";

        client().get().uri("/api/products/1")
                .header(GatewayHeaders.CORRELATION_ID, supplied)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(GatewayHeaders.CORRELATION_ID, supplied);

        assertThat(takeProxiedRequest().getHeader(GatewayHeaders.CORRELATION_ID)).isEqualTo(supplied);
    }

    @Test
    void overlongCorrelationIdIsReplaced() throws InterruptedException {
        String supplied = "a".repeat(200);

        String echoed = client().get().uri("/api/products/1")
                .header(GatewayHeaders.CORRELATION_ID, supplied)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseHeaders()
                .getFirst(GatewayHeaders.CORRELATION_ID);

        assertThat(echoed).isNotEqualTo(supplied).hasSizeLessThanOrEqualTo(64);
        assertThat(takeProxiedRequest().getHeader(GatewayHeaders.CORRELATION_ID)).isEqualTo(echoed);
    }

    @Test
    void correlationIdWithForbiddenCharactersIsReplaced() throws InterruptedException {
        // A literal newline is rejected by the HTTP client itself before it can be sent, so the
        // header-splitting case is covered by CorrelationIdTest; this is the on-the-wire variant.
        String supplied = "evil value; drop table orders";

        String echoed = client().get().uri("/api/products/1")
                .header(GatewayHeaders.CORRELATION_ID, supplied)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseHeaders()
                .getFirst(GatewayHeaders.CORRELATION_ID);

        assertThat(echoed).isNotEqualTo(supplied);
        assertThat(takeProxiedRequest().getHeader(GatewayHeaders.CORRELATION_ID)).isEqualTo(echoed);
    }

    @Test
    void correlationIdIsAlsoOnRejectedRequests() {
        client().get().uri("/api/orders/x")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(GatewayHeaders.CORRELATION_ID);
    }

    // ---------------------------------------------------------------- CORS

    @Test
    void preflightFromTheAllowedOriginSucceeds() {
        client().options().uri("/api/orders/x")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN);
    }

    @Test
    void preflightFromAnotherOriginIsRejected() {
        client().options().uri("/api/orders/x")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
