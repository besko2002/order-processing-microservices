package com.example.orders.inventory.client;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client inventory-service uses to ask order-service about an order's state, for the
 * operator reconciliation only (same shape as order-service's own inventory client).
 *
 * <p>Timeouts are short and explicit: the call sits inside an administrator's request, and a
 * stalled connection must cost a bounded time, not as long as the socket stays half-open. Redirects
 * are switched off — the bearer token is forwarded to the configured base URL and nowhere else.
 */
@Configuration(proxyBeanMethods = false)
class OrderClientConfiguration {

    /**
     * @param baseUrl        where order-service listens ({@code ORDER_BASE_URL})
     * @param connectTimeout how long to wait for the TCP connection
     * @param readTimeout    how long to wait for each read once connected
     * @return a client pinned to order-service's base URL
     */
    @Bean
    RestClient orderRestClient(
            @Value("${ORDER_BASE_URL:http://localhost:8082}") String baseUrl,
            @Value("${ORDER_CONNECT_TIMEOUT_MS:1000}") long connectTimeout,
            @Value("${ORDER_READ_TIMEOUT_MS:2000}") long readTimeout) {
        return create(baseUrl, connectTimeout, readTimeout);
    }

    static RestClient create(String baseUrl, long connectTimeoutMs, long readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
