package com.example.orders.order;

import com.example.orders.order.domain.OrderRepository;
import com.example.orders.support.outbox.OutboxRepository;
import com.example.orders.support.correlation.CorrelationId;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
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
 * Shared environment for the order-service integration tests: one real Postgres (so Flyway and
 * {@code ddl-auto=validate} are actually exercised), one real Kafka (so the relay publishes to a
 * broker rather than to a mock) and one {@link MockWebServer} that plays <em>both</em> of
 * order-service's HTTP dependencies — auth-service's JWKS endpoint and inventory-service's product
 * catalog.
 *
 * <p>Containers and stub are started once per JVM from a static initialiser rather than per class,
 * and every subclass that does not override a property shares one Spring context: Docker Desktop
 * on this machine has ~4 GB.
 *
 * <p>{@code public} only so that a test living next to the package-private saga classes
 * ({@code com.example.orders.order.saga}) can share this environment.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class OrderIntegrationTest {

    /** Issuer the service is configured to trust in these tests. */
    protected static final String ISSUER = "order-processing-auth";

    protected static final String JWKS_PATH = "/.well-known/jwks.json";

    protected static final String CATALOG_PATH_PREFIX = "/api/products/";

    /** The key auth-service would sign with; its public half is what the stub publishes. */
    protected static final RSAKey SIGNING_KEY = generateKey("order-test-key");

    /** Products the catalog stub knows about, keyed by product id. */
    protected static final Map<UUID, StubProduct> CATALOG = new ConcurrentHashMap<>();

    /** Correlation ids the catalog stub saw, in call order. */
    protected static final List<String> CATALOG_CORRELATION_IDS = new CopyOnWriteArrayList<>();

    /**
     * What the catalog answers. Replaced by the resilience tests to simulate an outage; the
     * default serves {@link #CATALOG} and answers {@code 404} for anything else.
     */
    protected static volatile Function<UUID, MockResponse> catalogBehaviour = OrderIntegrationTest::serveFromCatalog;

    @SuppressWarnings("resource")
    protected static final MockWebServer HTTP_STUB = new MockWebServer();

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @SuppressWarnings("resource")
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.2"));

    static {
        POSTGRES.start();
        KAFKA.start();
        HTTP_STUB.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith(JWKS_PATH)) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(new JWKSet(SIGNING_KEY.toPublicJWK()).toString());
                }
                if (path.startsWith(CATALOG_PATH_PREFIX)) {
                    CATALOG_CORRELATION_IDS.add(String.valueOf(request.getHeader(CorrelationId.HEADER)));
                    return catalogBehaviour.apply(productIdIn(path));
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        try {
            HTTP_STUB.start();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not start the HTTP stub", e);
        }
    }

    @DynamicPropertySource
    static void orderProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("AUTH_JWKS_URI", () -> stubUrl(JWKS_PATH));
        registry.add("AUTH_JWT_ISSUER", () -> ISSUER);
        registry.add("INVENTORY_BASE_URL", () -> stubUrl(""));
        // Keep the relay lively so a test does not have to wait half a second for every event.
        registry.add("OUTBOX_POLL_MS", () -> 250);
        // The retry must not make every failing test three times as slow.
        registry.add("INVENTORY_RETRY_WAIT", () -> "20ms");
        // The circuit breaker deliberately keeps its production settings here (ten calls before it
        // judges anything), so the handful of failing calls these tests provoke cannot open it.
        // InventoryResilienceTest tightens it inside its own context, and it has to be the only
        // place that does: a @DynamicPropertySource on a subclass cannot outrank this one.
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected OrderRepository orders;

    @Autowired
    protected OutboxRepository outbox;

    /**
     * Every test starts from an empty database and an empty catalog.
     *
     * <p>Cleanup is a bulk {@code DELETE} per table, never "load the entities, then delete them
     * one by one": a context with live scheduled work (the sweeper, the outbox relay, the saga
     * listeners — {@code OrderRefundRecoveryTest} runs a 300 ms sweep on purpose) may commit a new
     * {@code @Version} of an order between the load and the delete, and the versioned per-entity
     * delete then fails with {@code ObjectOptimisticLockingFailureException} before the test body
     * even starts. A statement-level delete has no version to lose: it waits for a concurrent
     * writer's row lock, removes the committed row, and the writer — if it comes second — finds
     * its row gone, rolls back (outbox row included) and, in the sweeper's case, stands down on
     * the retry.
     *
     * <p>Orders go first, then the outbox: once no order is left, nothing in flight can queue a
     * command for one any more (a transaction that already queued one commits before the order
     * delete finishes, so the outbox delete that follows sees and removes its row too). Ordering
     * across the two tables is all the atomicity needed — there is no foreign key between them.
     * {@code order_items} is {@code ON DELETE CASCADE} ({@code V2__create_orders.sql}, its only
     * foreign key into {@code orders}), so the lines go with their orders inside the same
     * statement; a TRUNCATE would take an exclusive lock and stall behind every open transaction
     * instead, which is why it is not used.
     */
    @BeforeEach
    void resetState() {
        orders.deleteAllInBatch();
        outbox.deleteAllInBatch();
        CATALOG.clear();
        CATALOG_CORRELATION_IDS.clear();
        catalogBehaviour = OrderIntegrationTest::serveFromCatalog;
    }

    // ------------------------------------------------------------------ catalog stub

    /**
     * A product as inventory-service would return it from {@code GET /api/products/{id}}.
     *
     * @param id    product id
     * @param sku   business key
     * @param name  display name
     * @param price unit price
     */
    protected record StubProduct(UUID id, String sku, String name, BigDecimal price) {
    }

    /**
     * @param sku   business key
     * @param name  display name
     * @param price unit price
     * @return the id the catalog stub will now serve
     */
    protected static UUID givenCatalogProduct(String sku, String name, String price) {
        UUID id = UUID.randomUUID();
        CATALOG.put(id, new StubProduct(id, sku, name, new BigDecimal(price)));
        return id;
    }

    /**
     * @param behaviour what the catalog answers from now on, per requested product id
     */
    protected static void catalogAnswers(Function<UUID, MockResponse> behaviour) {
        catalogBehaviour = behaviour;
    }

    private static MockResponse serveFromCatalog(UUID productId) {
        StubProduct product = CATALOG.get(productId);
        if (product == null) {
            return new MockResponse()
                    .setResponseCode(404)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"status\":404,\"message\":\"Product %s does not exist\"}".formatted(productId));
        }
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"%s","sku":"%s","name":"%s","description":null,"price":%s,"availableQuantity":500}"""
                        .formatted(product.id(), product.sku(), product.name(), product.price().toPlainString()));
    }

    private static UUID productIdIn(String path) {
        String tail = path.substring(CATALOG_PATH_PREFIX.length());
        int query = tail.indexOf('?');
        String id = query < 0 ? tail : tail.substring(0, query);
        try {
            return UUID.fromString(id);
        }
        catch (IllegalArgumentException e) {
            return new UUID(0L, 0L);
        }
    }

    private static String stubUrl(String path) {
        return "http://localhost:" + HTTP_STUB.getPort() + path;
    }

    // ------------------------------------------------------------------ request helpers

    /**
     * @param lines product id to quantity, in order
     * @return the JSON body of {@code POST /api/orders}
     */
    protected static String orderBody(Object... lines) {
        StringBuilder body = new StringBuilder("{\"items\":[");
        for (int i = 0; i < lines.length; i += 2) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"productId\":\"%s\",\"quantity\":%s}".formatted(lines[i], lines[i + 1]));
        }
        return body.append("]}").toString();
    }

    /**
     * @param body response body
     * @return it parsed
     */
    protected JsonNode parse(String body) throws IOException {
        return json.readTree(body);
    }

    /**
     * Polls until the condition holds. Used instead of a sleep so a fast machine does not pay for
     * a slow one's worst case.
     *
     * @param timeout   how long to keep trying
     * @param condition what has to become true
     */
    protected static void awaitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting", e);
            }
        }
        throw new AssertionError("Condition did not become true within " + timeout);
    }

    // ------------------------------------------------------------------ tokens

    /** @return a token with the USER role for a fresh random user id */
    protected static String userToken() {
        return token(UUID.randomUUID(), "USER");
    }

    /** @return a token with the ADMIN role for a fresh random user id */
    protected static String adminToken() {
        return token(UUID.randomUUID(), "ADMIN");
    }

    /**
     * @param userId {@code sub} claim
     * @param roles  values of the {@code roles} claim
     * @return a token the service must accept
     */
    protected static String token(UUID userId, String... roles) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(30))))
                .claim("email", "user@orders.local")
                .claim("name", "Test User")
                .claim("roles", List.of(roles))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(SIGNING_KEY.toRSAPrivateKey()));
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
