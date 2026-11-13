package com.example.orders.order.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client order-service uses to price an order against inventory-service.
 *
 * <p>Timeouts are short and explicit. This call sits inside a user-facing {@code POST}, so the
 * worst case a customer waits must be bounded by us, not by the other service's health: a stalled
 * connection that is never cut would hold a request thread and a database connection for as long
 * as the socket stays half-open.
 */
@Configuration(proxyBeanMethods = false)
class InventoryClientConfiguration {

    /**
     * @param baseUrl        where inventory-service listens
     * @param connectTimeout how long to wait for the TCP connection
     * @param readTimeout    how long to wait for the response once connected
     * @return a client pinned to the catalog's base URL
     */
    @Bean
    RestClient inventoryRestClient(
            @Value("${INVENTORY_BASE_URL:http://localhost:8083}") String baseUrl,
            @Value("${INVENTORY_CONNECT_TIMEOUT_MS:1000}") long connectTimeout,
            @Value("${INVENTORY_READ_TIMEOUT_MS:2000}") long readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeout));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeout));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
