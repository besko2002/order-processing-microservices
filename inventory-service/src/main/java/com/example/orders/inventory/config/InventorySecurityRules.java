package com.example.orders.inventory.config;

import com.example.orders.support.security.ServiceAuthorizationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * inventory-service's own slice of the shared filter chain.
 *
 * <p>service-support owns the mechanics (JWT re-validation, stateless session, error bodies) but
 * knows nothing about products, so the paths are declared here — and they mirror the gateway's
 * rules exactly: the catalog is public for reads, stock administration is ADMIN only. The gateway
 * enforcing the same thing is a convenience for clients, not the security boundary.
 */
@Configuration(proxyBeanMethods = false)
class InventorySecurityRules {

    @Bean
    ServiceAuthorizationCustomizer inventoryAuthorizationRules() {
        return registry -> registry
                // Browsing the catalog needs no account; writing to it is not reachable here.
                .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                .requestMatchers("/api/inventory/**").hasRole("ADMIN");
    }
}
