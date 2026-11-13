package com.example.orders.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything auth-service needs beyond the datasource: JWT signing/validation settings and
 * the credentials of the admin account seeded at startup.
 *
 * @param jwt   RS256 token settings
 * @param admin idempotently seeded administrator
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(Jwt jwt, Admin admin) {

    /**
     * @param issuer     value of the {@code iss} claim, also enforced when validating tokens
     * @param expiration token lifetime
     * @param keyId      {@code kid} published in the JWK Set; random UUID when blank
     * @param privateKey PKCS#8 PEM private key; blank means "generate an ephemeral pair"
     * @param publicKey  X.509 PEM public key; blank means "generate an ephemeral pair"
     */
    public record Jwt(
            String issuer,
            Duration expiration,
            String keyId,
            String privateKey,
            String publicKey) {
    }

    /**
     * @param email    admin login, normalised like any other email
     * @param password plain password, stored BCrypt-hashed
     * @param name     display name
     */
    public record Admin(String email, String password, String name) {
    }
}
