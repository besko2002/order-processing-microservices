package com.example.orders.gateway.ratelimit;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Buckets authenticated traffic per user ({@code sub}) and anonymous traffic per client IP, so
 * one noisy login page cannot spend the quota of a signed-in user behind the same NAT.
 *
 * <p>The IP is the socket peer, not {@code X-Forwarded-For}: the gateway is the outermost hop
 * here, and trusting a client-controlled header would make the anonymous bucket trivially
 * evadable. Revisit this if a real proxy is ever put in front.
 */
class UserOrIpKeyResolver implements KeyResolver {

    private static final String UNKNOWN_CLIENT = "ip:unknown";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(token -> "user:" + token.getToken().getSubject())
                .switchIfEmpty(Mono.fromSupplier(() -> clientIp(exchange)));
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return UNKNOWN_CLIENT;
        }
        return "ip:" + remoteAddress.getAddress().getHostAddress();
    }
}
