package com.example.orders.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared environment for the gateway integration tests: one stub server standing in for all
 * five downstream services <em>and</em> for auth-service's JWKS endpoint, one Redis container
 * for the rate limiter, and one RSA key pair generated in-process so the tests never depend on
 * a running auth-service.
 */
abstract class GatewayTestEnvironment {

    /** Issuer the gateway is configured to trust in these tests. */
    protected static final String ISSUER = "order-processing-auth";

    protected static final String JWKS_PATH = "/.well-known/jwks.json";

    /** Browser origin the gateway is configured to accept in these tests. */
    protected static final String ALLOWED_ORIGIN = "http://localhost:5173";

    /** Key auth-service would sign with; its public half is what the stub publishes. */
    protected static final RSAKey SIGNING_KEY = generateKey("gateway-test-key");

    /** A second, never-published key: tokens signed with it must be rejected. */
    protected static final RSAKey ROGUE_KEY = generateKey("gateway-test-key");

    @SuppressWarnings("resource")
    protected static final MockWebServer STUB = new MockWebServer();

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    static {
        REDIS.start();
        STUB.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith(JWKS_PATH)) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(new JWKSet(SIGNING_KEY.toPublicJWK()).toString());
                }
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"stub\":\"" + path + "\"}");
            }
        });
        try {
            STUB.start();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not start the downstream stub", e);
        }
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        String stubBaseUrl = "http://localhost:" + STUB.getPort();
        registry.add("AUTH_SERVICE_URI", () -> stubBaseUrl);
        registry.add("ORDER_SERVICE_URI", () -> stubBaseUrl);
        registry.add("INVENTORY_SERVICE_URI", () -> stubBaseUrl);
        registry.add("PAYMENT_SERVICE_URI", () -> stubBaseUrl);
        registry.add("NOTIFICATION_SERVICE_URI", () -> stubBaseUrl);
        registry.add("AUTH_JWKS_URI", () -> stubBaseUrl + JWKS_PATH);
        registry.add("AUTH_JWT_ISSUER", () -> ISSUER);
        registry.add("GATEWAY_CORS_ORIGINS", () -> ALLOWED_ORIGIN);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** JWKS fetches and leftovers from a previous test must not confuse the assertions. */
    @BeforeEach
    void drainStubRequests() throws InterruptedException {
        while (STUB.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // discard
        }
    }

    /**
     * @return the next request the stub received that was proxied through the gateway,
     *         skipping the decoder's JWKS lookups
     */
    protected static RecordedRequest takeProxiedRequest() throws InterruptedException {
        RecordedRequest request;
        do {
            request = STUB.takeRequest(5, TimeUnit.SECONDS);
        }
        while (request != null && request.getPath() != null && request.getPath().startsWith(JWKS_PATH));
        return request;
    }

    /**
     * @param subject {@code sub} claim, a user id
     * @param roles   values of the {@code roles} claim, e.g. {@code ["USER"]}
     * @return a token the gateway must accept
     */
    protected static String validToken(String subject, String... roles) {
        Instant now = Instant.now();
        return sign(SIGNING_KEY, ISSUER, subject, List.of(roles), now, now.plus(Duration.ofMinutes(30)));
    }

    /** @return a token whose {@code exp} is well outside the validator's clock skew */
    protected static String expiredToken(String subject) {
        Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
        return sign(SIGNING_KEY, ISSUER, subject, List.of("USER"), issuedAt, issuedAt.plus(Duration.ofMinutes(30)));
    }

    /** @return a token with the expected issuer and key id but signed by an unknown key */
    protected static String tokenSignedByAnotherKey(String subject) {
        Instant now = Instant.now();
        return sign(ROGUE_KEY, ISSUER, subject, List.of("USER"), now, now.plus(Duration.ofMinutes(30)));
    }

    /** @return a correctly signed token carrying somebody else's issuer */
    protected static String tokenFromAnotherIssuer(String subject) {
        Instant now = Instant.now();
        return sign(SIGNING_KEY, "https://evil.example/auth", subject, List.of("USER"), now,
                now.plus(Duration.ofMinutes(30)));
    }

    /** @return a fresh user id, which also gives the test its own rate-limit bucket */
    protected static String newUserId() {
        return UUID.randomUUID().toString();
    }

    private static String sign(RSAKey key, String issuer, String subject, List<String> roles, Instant issuedAt,
            Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("email", "user@orders.local")
                .claim("name", "Test User")
                .claim("roles", roles)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
        }
        catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the test token", e);
        }
        return jwt.serialize();
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(keyId)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        }
        catch (JOSEException e) {
            throw new IllegalStateException("Could not generate the test RSA key", e);
        }
    }
}
