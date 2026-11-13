package com.example.orders.support.security;

import com.example.orders.support.error.ServiceErrorAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless resource-server security for every servlet service behind the gateway.
 *
 * <p>The gateway already validated the bearer token, and this validates it <em>again</em> — same
 * JWKS, same issuer. That is the point: a service must be safe when it is reached directly (a port
 * forward, another pod, a future internal caller), so the edge is a first gate and never the only
 * one. Nothing here trusts {@code X-User-Id} or {@code X-User-Roles}; only the signature counts.
 *
 * <p>Paths stay with the services: this configuration opens the actuator health/info probes,
 * applies every {@link ServiceAuthorizationCustomizer} bean, and then requires authentication for
 * whatever is left.
 */
@AutoConfiguration(
        after = ServiceErrorAutoConfiguration.class,
        before = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
                OAuth2ResourceServerAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({SecurityFilterChain.class, JwtDecoder.class})
public class ServiceSecurityAutoConfiguration {

    /** Claim auth-service puts the role names in, always an array (e.g. {@code ["ADMIN"]}). */
    public static final String ROLES_CLAIM = "roles";

    /**
     * Keys are fetched from the JWKS URI on first use and cached, so a service starts fine while
     * auth-service is still coming up.
     *
     * @param jwksUri JWK Set published by auth-service
     * @param issuer  value the {@code iss} claim must carry
     * @return decoder rejecting anything not signed by auth-service, expired, or from another issuer
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(
            @Value("${AUTH_JWKS_URI:http://localhost:8081/.well-known/jwks.json}") String jwksUri,
            @Value("${AUTH_JWT_ISSUER:order-processing-auth}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /** Maps the {@code roles} claim onto {@code ROLE_*} authorities, as the gateway does. */
    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix(CurrentUser.ROLE_PREFIX);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * @param http                    chain builder
     * @param jwtDecoder              validates the bearer token again inside the service
     * @param jwtAuthenticationConverter turns {@code roles} into authorities
     * @param authenticationEntryPoint renders 401 in the shared error shape
     * @param accessDeniedHandler     renders 403 in the shared error shape
     * @param customizers             per-service authorisation rules
     * @return the single stateless filter chain
     * @throws Exception when the chain cannot be built
     */
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain serviceSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            ObjectProvider<ServiceAuthorizationCustomizer> customizers) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    // Liveness/readiness probes must answer before any token exists.
                    requests.requestMatchers(
                            "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    customizers.orderedStream().forEach(customizer -> customizer.customize(requests));
                    requests.anyRequest().authenticated();
                })
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
