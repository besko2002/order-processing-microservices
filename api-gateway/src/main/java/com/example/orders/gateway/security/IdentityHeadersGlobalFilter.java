package com.example.orders.gateway.security;

import java.util.stream.Collectors;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Hands the verified identity to the downstream service.
 *
 * <p>Two rules, in this order:
 * <ol>
 *   <li>{@code X-User-Id} and {@code X-User-Roles} coming from the client are <em>always</em>
 *       dropped, authenticated or not — otherwise anyone could claim to be an admin simply by
 *       setting a header.</li>
 *   <li>They are re-added from the validated token only. The {@code Authorization} header is
 *       left exactly as it arrived so services can verify the token themselves.</li>
 * </ol>
 */
@Component
class IdentityHeadersGlobalFilter implements GlobalFilter, Ordered {

    /** Runs early, well before the routing filter that copies the headers onto the upstream call. */
    static final int ORDER = 0;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .filter(Authentication::isAuthenticated)
                .map(token -> withIdentity(exchange, token))
                .defaultIfEmpty(withoutIdentity(exchange))
                .flatMap(chain::filter);
    }

    private static ServerWebExchange withoutIdentity(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(GatewayHeaders.USER_ID);
                    headers.remove(GatewayHeaders.USER_ROLES);
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private static ServerWebExchange withIdentity(ServerWebExchange exchange, JwtAuthenticationToken token) {
        String roles = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
                .collect(Collectors.joining(","));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(GatewayHeaders.USER_ID, token.getToken().getSubject());
                    headers.set(GatewayHeaders.USER_ROLES, roles);
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
