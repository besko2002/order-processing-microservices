package com.example.orders.gateway.security;

import com.example.orders.gateway.config.GatewayEdgeProperties;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Edge authentication and authorisation.
 *
 * <p>Tokens are verified against auth-service's JWKS — the gateway never sees a private key
 * and never mints one. The bearer token is forwarded downstream untouched, because every
 * service re-validates it for itself (defense in depth); the gateway is a first gate, not the
 * only one.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewayEdgeProperties.class)
class SecurityConfig {

    /** Claim auth-service puts the role names in, always an array (e.g. {@code ["USER"]}). */
    static final String ROLES_CLAIM = "roles";

    /**
     * The optional replay guard of {@code POST /api/orders}. Defined by order-service's API, but the
     * gateway's CORS configuration must allow it or browser clients cannot send it (a pre-flight
     * that does not list a header makes the actual request fail before it is ever routed).
     */
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder,
            CorsConfigurationSource corsConfigurationSource,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                // Stateless: no session, no saved request, the token is the whole state.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(exchanges -> exchanges
                        // Pre-flights are answered by the CORS filter before this point; a
                        // pre-flight from a disallowed origin is already rejected there.
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // Public catalog: browsing products needs no account.
                        .pathMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        // Routing table is operational information.
                        .pathMatchers("/actuator/gateway/**").hasRole("ADMIN")
                        // Stock administration, as opposed to the public product catalog.
                        .pathMatchers("/api/inventory/**").hasRole("ADMIN")
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * Keys are fetched from the JWKS URI on first use and cached, so the gateway starts fine
     * while auth-service is still coming up.
     *
     * @param properties JWKS location and expected issuer
     * @return decoder rejecting anything not signed by auth-service, expired, or from another issuer
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewayEdgeProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(properties.security().jwksUri())
                .build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(properties.security().issuer());
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * @param properties allowed browser origins
     * @return CORS rules for {@code /**}; credentials are allowed, so the origin list stays exact
     *         (no wildcard origins alongside credentials), and the header list covers what the
     *         browser-driven clients actually need: the bearer token, the body content type and the
     *         {@code Idempotency-Key} of {@code POST /api/orders} — without it a pre-flight for the
     *         idempotent checkout would be refused and the browser client could not retry safely.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayEdgeProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(),
                HttpMethod.DELETE.name()));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                IDEMPOTENCY_KEY_HEADER));
        configuration.setExposedHeaders(List.of(GatewayHeaders.CORRELATION_ID));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** Maps the {@code roles} claim (e.g. {@code ["ADMIN"]}) onto {@code ROLE_ADMIN} authorities. */
    private static ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
