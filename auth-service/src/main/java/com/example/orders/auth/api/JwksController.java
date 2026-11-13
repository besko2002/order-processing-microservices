package com.example.orders.auth.api;

import com.example.orders.auth.security.AuthJwtKeys;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the signing key's public half as a JWK Set. This is the contract the API gateway
 * (phase 3) and the downstream services use to verify tokens without sharing any secret.
 */
@RestController
public class JwksController {

    /** Standard discovery path for a JWK Set. */
    public static final String PATH = "/.well-known/jwks.json";

    private final AuthJwtKeys keys;

    JwksController(AuthJwtKeys keys) {
        this.keys = keys;
    }

    /** @return the JWK Set containing exactly one public RSA key */
    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return keys.publicJwkSet().toJSONObject();
    }
}
