package com.example.orders.auth.security;

import com.example.orders.auth.config.AuthProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * Wires the encoder used to issue tokens and the decoder auth-service uses to validate its
 * own tokens on {@code /api/auth/me}. Validation is purely local: the public key is already in
 * memory, so no call to the JWKS endpoint is made.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

    @Bean
    JwtEncoder jwtEncoder(AuthJwtKeys keys) {
        return new NimbusJwtEncoder(keys.jwkSource());
    }

    @Bean
    JwtDecoder jwtDecoder(AuthJwtKeys keys, AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        // Default validators cover exp/nbf; the issuer check is added on top.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()));
        return decoder;
    }
}
