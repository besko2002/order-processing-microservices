package com.example.orders.inventory;

import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ProductRepository;
import com.example.orders.inventory.domain.StockReservationRepository;
import com.example.orders.inventory.service.ProductService;
import com.example.orders.inventory.service.StockReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared environment for the inventory integration tests: one real Postgres (so Flyway and
 * {@code ddl-auto=validate} are actually exercised), one real Kafka (so the phase-6 saga consumers
 * and the outbox relay talk to a broker instead of a mock) and one stub standing in for
 * auth-service's JWKS endpoint, with an RSA key generated in-process — the same approach as the
 * gateway tests, so these tests never need a running auth-service.
 *
 * <p>Containers and stub are started once per JVM from a static initialiser rather than per class,
 * and every subclass shares the same Spring context: Docker Desktop on this machine has ~4 GB.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class InventoryIntegrationTest {

    /** Issuer the service is configured to trust in these tests. */
    protected static final String ISSUER = "order-processing-auth";

    protected static final String JWKS_PATH = "/.well-known/jwks.json";

    /** The key auth-service would sign with; its public half is what the stub publishes. */
    protected static final RSAKey SIGNING_KEY = generateKey("inventory-test-key");

    /** A second, never-published key: tokens signed with it must be rejected. */
    protected static final RSAKey ROGUE_KEY = generateKey("inventory-test-key");

    @SuppressWarnings("resource")
    protected static final MockWebServer JWKS_STUB = new MockWebServer();

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @SuppressWarnings("resource")
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.2"));

    static {
        POSTGRES.start();
        KAFKA.start();
        JWKS_STUB.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith(JWKS_PATH)) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(new JWKSet(SIGNING_KEY.toPublicJWK()).toString());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        try {
            JWKS_STUB.start();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not start the JWKS stub", e);
        }
    }

    @DynamicPropertySource
    static void inventoryProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The concurrency test runs 20 threads against short transactions; a pool of 5 would only
        // make them queue on the pool instead of on the row lock.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 16);
        registry.add("AUTH_JWKS_URI", () -> "http://localhost:" + JWKS_STUB.getPort() + JWKS_PATH);
        registry.add("AUTH_JWT_ISSUER", () -> ISSUER);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Keep the relay lively so a test does not wait half a second for every event it asserts on.
        registry.add("OUTBOX_POLL_MS", () -> 250);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected ProductRepository products;

    @Autowired
    protected StockReservationRepository reservations;

    @Autowired
    protected ProductService productService;

    @Autowired
    protected StockReservationService reservationService;

    /** Each test starts from an empty catalog; reservations go first because of the foreign key. */
    @BeforeEach
    void resetDatabase() {
        reservations.deleteAllInBatch();
        products.deleteAllInBatch();
    }

    /**
     * @param sku      business key
     * @param quantity starting available stock
     * @return a stored product
     */
    protected Product givenProduct(String sku, int quantity) {
        return givenProduct(sku, "Product " + sku, new BigDecimal("10.00"), quantity);
    }

    /**
     * @param sku      business key
     * @param name     display name
     * @param price    unit price
     * @param quantity starting available stock
     * @return a stored product
     */
    protected Product givenProduct(String sku, String name, BigDecimal price, int quantity) {
        return products.saveAndFlush(Product.create(sku, name, "seeded by a test", price, quantity));
    }

    /** @return a token with the ADMIN role, as auth-service would issue it */
    protected static String adminToken() {
        return token(UUID.randomUUID(), "ADMIN");
    }

    /** @return a token with the USER role only */
    protected static String userToken() {
        return token(UUID.randomUUID(), "USER");
    }

    /**
     * @param userId {@code sub} claim
     * @param roles  values of the {@code roles} claim
     * @return a token the service must accept
     */
    protected static String token(UUID userId, String... roles) {
        Instant now = Instant.now();
        return sign(SIGNING_KEY, ISSUER, userId.toString(), List.of(roles), now, now.plus(Duration.ofMinutes(30)));
    }

    /** @return an ADMIN token signed by a key the JWKS never published */
    protected static String tokenSignedByAnotherKey() {
        Instant now = Instant.now();
        return sign(ROGUE_KEY, ISSUER, UUID.randomUUID().toString(), List.of("ADMIN"), now,
                now.plus(Duration.ofMinutes(30)));
    }

    /** @return an ADMIN token whose {@code exp} is well in the past */
    protected static String expiredAdminToken() {
        Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
        return sign(SIGNING_KEY, ISSUER, UUID.randomUUID().toString(), List.of("ADMIN"), issuedAt,
                issuedAt.plus(Duration.ofMinutes(30)));
    }

    /** @return a correctly signed ADMIN token from an issuer this service does not trust */
    protected static String tokenFromAnotherIssuer() {
        Instant now = Instant.now();
        return sign(SIGNING_KEY, "https://evil.example/auth", UUID.randomUUID().toString(), List.of("ADMIN"), now,
                now.plus(Duration.ofMinutes(30)));
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
