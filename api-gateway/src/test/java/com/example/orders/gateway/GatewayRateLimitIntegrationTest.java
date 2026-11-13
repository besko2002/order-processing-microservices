package com.example.orders.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The 429 path, with a bucket small enough to empty in a handful of requests. A fresh user id
 * per test keeps the bucket independent of anything the other test classes left in Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "RATE_LIMIT_REPLENISH_RATE=1",
                "RATE_LIMIT_BURST_CAPACITY=2",
                "RATE_LIMIT_REQUESTED_TOKENS=1",
                // Own Redis database: the buckets the other test class fills must not be visible here.
                "spring.data.redis.database=1"
        })
class GatewayRateLimitIntegrationTest extends GatewayTestEnvironment {

    @LocalServerPort
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void burstIsAllowedAndTheNextRequestsGet429WithTheSharedErrorBody() {
        String token = "Bearer " + validToken(newUserId(), "USER");

        List<EntityExchangeResult<byte[]>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(client().get().uri("/api/orders/x")
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .exchange()
                    .expectBody()
                    .returnResult());
        }

        List<Integer> statuses = results.stream().map(result -> result.getStatus().value()).toList();
        assertThat(statuses).as("burst of %s must be served", 2).startsWith(200, 200);
        assertThat(statuses).as("statuses were %s", statuses).contains(429);

        EntityExchangeResult<byte[]> throttled = results.stream()
                .filter(result -> result.getStatus().value() == 429)
                .findFirst()
                .orElseThrow();
        assertThat(throttled.getResponseHeaders().getContentType()).isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
        assertThat(new String(throttled.getResponseBodyContent()))
                .contains("\"status\":429")
                .contains("\"error\":\"Too Many Requests\"")
                .contains("\"path\":\"/api/orders/x\"");
        assertThat(throttled.getResponseHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
        assertThat(throttled.getResponseHeaders().getFirst("X-RateLimit-Burst-Capacity")).isEqualTo("2");
        assertThat(throttled.getResponseHeaders().getFirst("X-RateLimit-Replenish-Rate")).isEqualTo("1");
    }

    @Test
    void anonymousTrafficIsBucketedByClientIp() {
        // The public catalog has no user id to key on, so the client IP has to carry the limit.
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            statuses.add(client().get().uri("/api/products/1")
                    .exchange()
                    .expectBody()
                    .returnResult()
                    .getStatus()
                    .value());
        }

        assertThat(statuses).as("statuses were %s", statuses).contains(429);
    }
}
