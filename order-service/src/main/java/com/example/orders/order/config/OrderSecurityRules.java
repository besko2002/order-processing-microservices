package com.example.orders.order.config;

import com.example.orders.support.security.ServiceAuthorizationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * order-service's own slice of the shared filter chain.
 *
 * <p>Nothing about an order is public, so there is exactly one rule to declare: the cross-customer
 * list is for administrators. Everything else under {@code /api/orders} falls through to the
 * closing {@code anyRequest().authenticated()} of service-support, and the per-order ownership
 * check happens in the service — a path rule cannot express "the caller owns this row".
 *
 * <p>The matcher is the exact path {@code /api/orders}, so {@code /api/orders/me} and
 * {@code /api/orders/{id}} are deliberately not caught by it.
 */
@Configuration(proxyBeanMethods = false)
class OrderSecurityRules {

    @Bean
    ServiceAuthorizationCustomizer orderAuthorizationRules() {
        return registry -> registry
                .requestMatchers(HttpMethod.GET, "/api/orders").hasRole("ADMIN");
    }
}
