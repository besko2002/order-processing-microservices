package com.example.orders.gateway.ratelimit;

import com.example.orders.gateway.error.GatewayErrorWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gives the 429 produced by the stock {@code RequestRateLimiter} filter the same JSON error
 * body every other rejection uses.
 *
 * <p>The filter factory itself only sets the status and the {@code X-RateLimit-*} headers and
 * then completes the response, so the body has to be supplied by decorating the response
 * before the rate limiter runs — hence the very high precedence.
 */
@Component
class RateLimitErrorBodyGlobalFilter implements GlobalFilter, Ordered {

    private static final String MESSAGE = "Rate limit exceeded, retry later";

    private final GatewayErrorWriter errorWriter;

    RateLimitErrorBodyGlobalFilter(GatewayErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        ServerHttpResponse decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {

            @Override
            public Mono<Void> setComplete() {
                HttpStatusCode status = getStatusCode();
                if (!isCommitted() && HttpStatus.TOO_MANY_REQUESTS.equals(status)) {
                    return errorWriter.write(this, path, HttpStatus.TOO_MANY_REQUESTS, MESSAGE);
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(decorated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
