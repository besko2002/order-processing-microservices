package com.example.orders.inventory.client;

import com.example.orders.support.correlation.CorrelationId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The one place inventory-service asks order-service what state an order is in — the
 * authoritative source for "may this order's stock be released?".
 *
 * <p>Inventory cannot read the order database, and it must not take an operator's word for an
 * order's state. It therefore calls {@code GET /api/orders/{id}} (which answers any order to an
 * ADMIN) <em>with the administrator's own bearer token</em>: no service credential exists, nothing
 * is cached, and order-service's own authorization decides. {@code CANCELLED} and {@code REJECTED}
 * are immutable terminal states, so an answer read here stays true while the caller then runs its
 * own transaction; every other state, and every doubtful answer, is a refusal.
 *
 * <p>Fail-closed contract of {@link #check}: it never throws for a remote problem and never returns
 * a releasable verdict unless the response is a {@code 200} whose body is a JSON object whose
 * {@code id} is exactly the requested order id and whose {@code status} is the exact string
 * {@code CANCELLED} or {@code REJECTED}. Redirects are not followed (the token must not travel
 * anywhere but the configured base URL), bodies are capped, and connect/read timeouts bound every
 * call. The token is only ever put into the {@code Authorization} header — never logged.
 */
@Component
public class OrderStatusClient {

    /** Largest order-service answer that is parsed; a bigger body is treated as untrustworthy. */
    public static final int MAX_BODY_BYTES = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(OrderStatusClient.class);

    /** Every status order-service knows that is neither of the two releasable ones. */
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "PENDING", "STOCK_RESERVED", "PAYMENT_PENDING", "CONFIRMED", "CANCELLATION_PENDING");

    private final RestClient restClient;
    private final ObjectMapper mapper;

    OrderStatusClient(RestClient orderRestClient, ObjectMapper mapper) {
        this.restClient = orderRestClient;
        this.mapper = mapper;
    }

    /**
     * @param orderId     order to look up
     * @param bearerToken the calling administrator's JWT, forwarded as-is
     * @return the verdict; never {@code null}, never an exception for a remote failure
     * @throws IllegalArgumentException for a missing order id or token (a programming error, not a
     *                                  remote condition)
     */
    public OrderStatusVerdict check(UUID orderId, String bearerToken) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("a bearer token is required to ask order-service");
        }
        try {
            OrderStatusVerdict verdict = restClient.get()
                    .uri("/api/orders/{id}", orderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .header(CorrelationId.HEADER, CorrelationId.sanitise(MDC.get(CorrelationId.MDC_KEY)))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> interpret(orderId, response));
            return verdict == null ? OrderStatusVerdict.UNVERIFIABLE : verdict;
        }
        catch (ResourceAccessException e) {
            // Connect/read timeout, refused or reset connection. Only the exception type is
            // logged: its message may echo the request URL, and nothing here may echo credentials.
            log.warn("order-service is not reachable for order {}: {}", orderId, e.getClass().getSimpleName());
            return OrderStatusVerdict.UNAVAILABLE;
        }
        catch (RestClientException e) {
            log.warn("order-service answer for order {} could not be processed: {}",
                    orderId, e.getClass().getSimpleName());
            return OrderStatusVerdict.UNVERIFIABLE;
        }
        catch (RuntimeException e) {
            log.warn("order-service lookup for order {} failed unexpectedly: {}",
                    orderId, e.getClass().getSimpleName());
            return OrderStatusVerdict.UNAVAILABLE;
        }
    }

    private OrderStatusVerdict interpret(UUID orderId, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 200) {
            return readVerdict(orderId, response);
        }
        OrderStatusVerdict verdict;
        if (status == 404) {
            verdict = OrderStatusVerdict.NOT_FOUND;
        }
        else if (status == 401 || status == 403) {
            verdict = OrderStatusVerdict.ACCESS_DENIED;
        }
        else if (status >= 500 || status == 408 || status == 429) {
            verdict = OrderStatusVerdict.UNAVAILABLE;
        }
        else {
            // 2xx other than 200 (no body to trust), 3xx (never followed), other 4xx.
            verdict = OrderStatusVerdict.UNVERIFIABLE;
        }
        log.warn("order-service answered {} for order {}: {}", status, orderId, verdict);
        return verdict;
    }

    private OrderStatusVerdict readVerdict(UUID orderId, ClientHttpResponse response) throws IOException {
        byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length == 0 || body.length > MAX_BODY_BYTES) {
            return unverifiable(orderId, "body is empty or larger than " + MAX_BODY_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        }
        catch (IOException e) {
            return unverifiable(orderId, "body is not JSON");
        }
        if (root == null || !root.isObject()) {
            return unverifiable(orderId, "body is not a JSON object");
        }
        JsonNode id = root.get("id");
        if (id == null || !id.isTextual() || !orderId.toString().equalsIgnoreCase(id.asText())) {
            // Never act on an answer about another order, or about no order at all.
            return unverifiable(orderId, "body does not describe the requested order");
        }
        JsonNode state = root.get("status");
        if (state == null || !state.isTextual()) {
            return unverifiable(orderId, "status is missing or not a string");
        }
        String value = state.asText();
        if ("CANCELLED".equals(value)) {
            return OrderStatusVerdict.CANCELLED;
        }
        if ("REJECTED".equals(value)) {
            return OrderStatusVerdict.REJECTED;
        }
        if (ACTIVE_STATUSES.contains(value)) {
            return OrderStatusVerdict.ACTIVE;
        }
        return unverifiable(orderId, "status is not one this service knows");
    }

    private static OrderStatusVerdict unverifiable(UUID orderId, String why) {
        log.warn("order-service answer for order {} cannot be trusted: {}", orderId, why);
        return OrderStatusVerdict.UNVERIFIABLE;
    }
}
