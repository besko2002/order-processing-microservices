package com.example.orders.support.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * How a service contributes its own authorisation rules to the shared filter chain.
 *
 * <p>This module owns the mechanics (JWT validation, statelessness, error bodies) but must not own
 * any service's URL space, so each service publishes one bean like this:
 *
 * <pre>{@code
 * @Bean
 * ServiceAuthorizationCustomizer inventoryRules() {
 *     return registry -> registry
 *             .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
 *             .requestMatchers("/api/inventory/**").hasRole("ADMIN");
 * }
 * }</pre>
 *
 * <p>Rules are applied in bean order ({@code @Order} is honoured), after the public actuator
 * endpoints and before the closing {@code anyRequest().authenticated()} — first match wins, so a
 * customizer can only widen or narrow paths it names itself.
 */
@FunctionalInterface
public interface ServiceAuthorizationCustomizer {

    /**
     * @param registry the registry mid-configuration; add {@code requestMatchers(...)} rules to it
     */
    void customize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry);
}
