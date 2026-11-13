package com.example.orders.gateway.security;

import com.example.orders.gateway.error.GatewayErrorWriter;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Turns "authenticated but not allowed here" into a 403 carrying the shared JSON error body. */
@Component
class RestAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final GatewayErrorWriter errorWriter;

    RestAccessDeniedHandler(GatewayErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return errorWriter.write(exchange, HttpStatus.FORBIDDEN, "Access denied");
    }
}
