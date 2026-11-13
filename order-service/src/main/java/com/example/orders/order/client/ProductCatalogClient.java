package com.example.orders.order.client;

import com.example.orders.support.correlation.CorrelationId;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The one place order-service talks to inventory-service over HTTP: a price snapshot for a
 * product that is about to be ordered.
 *
 * <p>This is a synchronous call in the middle of a write request, so it is guarded twice. A
 * <strong>retry</strong> absorbs the single dropped connection or the one slow node; a
 * <strong>circuit breaker</strong> stops us from queueing hundreds of order requests against a
 * catalog that is plainly down — once it is open, callers fail in microseconds with {@code 503}
 * instead of waiting out the timeout, and inventory-service gets room to recover.
 *
 * <p>The two guards are wired around the same annotated method, so the effective nesting is
 * {@code Retry(CircuitBreaker(call))} — every individual attempt is recorded by the breaker.
 *
 * <p>The correlation id of the incoming request is forwarded, so the catalog's log lines for this
 * price lookup carry the same id as the order that triggered it.
 */
@Component
public class ProductCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(ProductCatalogClient.class);

    /** Name of the resilience4j circuit breaker and retry instances configured for this client. */
    public static final String RESILIENCE_INSTANCE = "inventory";

    private final RestClient restClient;

    ProductCatalogClient(RestClient inventoryRestClient) {
        this.restClient = inventoryRestClient;
    }

    /**
     * @param productId product to price
     * @return the catalog's current view of that product
     * @throws UnknownProductException        when the catalog does not know the id ({@code 404})
     * @throws InventoryCallFailedException   when one attempt failed transport-side or with 5xx
     * @throws InventoryUnavailableException  when the breaker is open, or retries were exhausted
     */
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "catalogIsShutOff")
    @Retry(name = RESILIENCE_INSTANCE)
    public CatalogProduct fetch(UUID productId) {
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        try {
            CatalogProduct product = restClient.get()
                    .uri("/api/products/{id}", productId)
                    .header(CorrelationId.HEADER, CorrelationId.sanitise(correlationId))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new UnknownProductException(productId);
                    })
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new InventoryCallFailedException(
                                "Product catalog answered " + response.getStatusCode().value());
                    })
                    .body(CatalogProduct.class);
            if (product == null || product.price() == null) {
                throw new InventoryCallFailedException("Product catalog returned an empty body for " + productId);
            }
            return product;
        }
        catch (ResourceAccessException e) {
            // Connect or read timeout, refused connection, reset socket.
            throw new InventoryCallFailedException("Product catalog is not reachable", e);
        }
        catch (RestClientException e) {
            // Anything left: an unparsable body, a redirect loop. Same operational verdict.
            throw new InventoryCallFailedException("Product catalog call failed", e);
        }
    }

    /**
     * Fallback for an open breaker. Chosen by resilience4j only for
     * {@link CallNotPermittedException}, so real failures keep their own exception and their place
     * in the breaker's statistics.
     *
     * @param productId product that was not looked up
     * @param ex        the refusal from the open breaker
     * @return never returns
     */
    CatalogProduct catalogIsShutOff(UUID productId, CallNotPermittedException ex) {
        log.warn("Circuit breaker '{}' is open, not pricing product {}", RESILIENCE_INSTANCE, productId);
        throw new InventoryUnavailableException(
                "Product catalog is temporarily unavailable, please retry shortly");
    }
}
