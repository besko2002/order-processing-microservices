package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.order.client.ProductCatalogClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What a customer sees when inventory-service is unwell.
 *
 * <p>The breaker is deliberately tiny here — two calls and it has made up its mind — so the
 * transition can be asserted without hammering the stub. Everywhere else its minimum call count is
 * set high enough that it never opens, because a shared breaker would let one test's deliberate
 * outage fail the next test.
 */
class InventoryResilienceTest extends OrderIntegrationTest {

    @DynamicPropertySource
    static void tightenTheGuards(DynamicPropertyRegistry registry) {
        // One attempt per request keeps the arithmetic of "calls seen by the breaker" obvious.
        registry.add("INVENTORY_RETRY_MAX_ATTEMPTS", () -> 1);
        registry.add("INVENTORY_CB_WINDOW", () -> 4);
        registry.add("INVENTORY_CB_MINIMUM_CALLS", () -> 2);
        registry.add("INVENTORY_CB_FAILURE_RATE", () -> 50);
        // Long enough that the breaker cannot drift back to half-open mid-test.
        registry.add("INVENTORY_CB_OPEN_WAIT", () -> "60s");
        registry.add("INVENTORY_CONNECT_TIMEOUT_MS", () -> 300);
        registry.add("INVENTORY_READ_TIMEOUT_MS", () -> 300);
    }

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    private CircuitBreaker breaker() {
        return circuitBreakers.circuitBreaker(ProductCatalogClient.RESILIENCE_INSTANCE);
    }

    @BeforeEach
    void closeTheBreaker() {
        breaker().reset();
    }

    @Test
    void aCatalog5xxBecomes503NotAFailedOrder() throws Exception {
        catalogAnswers(id -> new MockResponse().setResponseCode(503));

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(UUID.randomUUID(), 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(header().string("Retry-After", "5"));

        assertThat(orders.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void aCatalogThatNeverAnswersBecomes503() throws Exception {
        // The socket is accepted and then left silent: the read timeout is what ends the call.
        catalogAnswers(id -> new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(UUID.randomUUID(), 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));

        assertThat(orders.count()).isZero();
    }

    @Test
    void theBreakerOpensAfterTheThresholdAndThenFailsWithoutCallingTheCatalog() throws Exception {
        catalogAnswers(id -> new MockResponse().setResponseCode(500));

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/orders")
                            .header("Authorization", "Bearer " + userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody(UUID.randomUUID(), 1)))
                    .andExpect(status().isServiceUnavailable());
        }

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsBeforeTheBreakerOpened = CATALOG_CORRELATION_IDS.size();
        assertThat(callsBeforeTheBreakerOpened).isEqualTo(2);

        // Even a healthy catalog is not consulted now: that is the point of an open breaker.
        catalogAnswers(id -> new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"%s\",\"sku\":\"S\",\"name\":\"N\",\"price\":1.00}".formatted(id)));

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(UUID.randomUUID(), 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Product catalog is temporarily unavailable, please retry shortly"));

        assertThat(CATALOG_CORRELATION_IDS).hasSize(callsBeforeTheBreakerOpened);
    }

    @Test
    void anUnknownProductNeverPushesTheBreakerTowardsOpening() throws Exception {
        // The default stub answers 404 for an id it does not know.
        for (int attempt = 0; attempt < 4; attempt++) {
            mvc.perform(post("/api/orders")
                            .header("Authorization", "Bearer " + userToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody(UUID.randomUUID(), 1)))
                    .andExpect(status().isBadRequest());
        }

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }
}
