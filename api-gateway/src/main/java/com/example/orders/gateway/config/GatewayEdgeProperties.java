package com.example.orders.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything the edge needs that is not already a Spring Cloud Gateway property: where to
 * fetch auth-service's public keys, which issuer to trust and which browser origin may call
 * the gateway.
 *
 * @param security JWT validation settings
 * @param cors     browser origins allowed to call the gateway
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayEdgeProperties(Security security, Cors cors) {

    /**
     * @param jwksUri JWK Set published by auth-service; keys are fetched lazily and cached,
     *                so the gateway may start before auth-service does
     * @param issuer  value the {@code iss} claim must carry
     */
    public record Security(
            @DefaultValue("http://localhost:8081/.well-known/jwks.json") String jwksUri,
            @DefaultValue("order-processing-auth") String issuer) {
    }

    /**
     * @param allowedOrigins exact origins (scheme + host + port) allowed to send credentials
     */
    public record Cors(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {
    }
}
