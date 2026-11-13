package com.example.orders.payment;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.Topics;
import com.example.orders.payment.domain.PaymentRepository;
import com.example.orders.support.idempotency.ProcessedEventRepository;
import com.example.orders.support.outbox.OutboxRepository;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
 * Shared environment for the payment-service tests: one real Postgres (Flyway and
 * {@code ddl-auto=validate} are exercised for real), one real Kafka (the commands arrive and the
 * events leave over a broker) and a stub standing in for auth-service's JWKS endpoint.
 *
 * <p>Containers and stub are started once per JVM, and every subclass shares one Spring context:
 * Docker Desktop on this machine has ~4 GB.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class PaymentIntegrationTest {

    /** Issuer the service is configured to trust in these tests. */
    protected static final String ISSUER = "order-processing-auth";

    protected static final String JWKS_PATH = "/.well-known/jwks.json";

    /** The key auth-service would sign with; its public half is what the stub publishes. */
    protected static final RSAKey SIGNING_KEY = generateKey("payment-test-key");

    /** Ceiling the simulated gateway is configured with in these tests. */
    protected static final String DECLINE_ABOVE = "1000.00";

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
    static void paymentProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("AUTH_JWKS_URI", () -> "http://localhost:" + JWKS_STUB.getPort() + JWKS_PATH);
        registry.add("AUTH_JWT_ISSUER", () -> ISSUER);
        // The gateway simulation, pinned so the tests assert against known thresholds.
        registry.add("PAYMENT_DECLINE_ABOVE", () -> DECLINE_ABOVE);
        registry.add("PAYMENT_FAILURE_RATE", () -> 0);
        // Keep the relay lively so a test does not wait half a second for every event.
        registry.add("OUTBOX_POLL_MS", () -> 250);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected PaymentRepository payments;

    @Autowired
    protected OutboxRepository outbox;

    @Autowired
    protected ProcessedEventRepository processedEvents;

    /** Every test starts from an empty store; the messaging tables go too. */
    @BeforeEach
    void resetDatabase() {
        outbox.deleteAllInBatch();
        processedEvents.deleteAllInBatch();
        payments.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ kafka helpers

    /**
     * @param key      message key, always the order id in this system
     * @param envelope the envelope to publish onto {@code payment.commands}
     */
    protected void publishCommand(UUID key, EventEnvelope<?> envelope) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    Topics.PAYMENT_COMMANDS, key.toString(), json.writeValueAsString(envelope));
            record.headers().add(EventHeaders.EVENT_ID, bytes(envelope.eventId().toString()));
            record.headers().add(EventHeaders.EVENT_TYPE, bytes(envelope.eventType()));
            record.headers().add(EventHeaders.CORRELATION_ID, bytes(envelope.correlationId()));
            producer.send(record).get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while producing", e);
        }
        catch (Exception e) {
            throw new AssertionError("Could not produce onto " + Topics.PAYMENT_COMMANDS, e);
        }
    }

    /**
     * @param orderId   order the event must be about
     * @param eventType type to wait for, e.g. {@code PaymentCompleted}
     * @return the parsed envelope
     */
    protected JsonNode awaitPaymentEvent(UUID orderId, String eventType) {
        JsonNode found = findPaymentEvent(orderId, eventType, Duration.ofSeconds(45));
        if (found == null) {
            throw new AssertionError("No " + eventType + " for order " + orderId + " arrived on "
                    + Topics.PAYMENT_EVENTS);
        }
        return found;
    }

    /**
     * @param orderId   order the event must be about
     * @param eventType type to look for
     * @param timeout   how long to wait
     * @return the parsed envelope, or {@code null} when nothing arrived in time
     */
    protected JsonNode findPaymentEvent(UUID orderId, String eventType, Duration timeout) {
        Properties properties = new Properties();
        properties.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "payment-assert-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(Topics.PAYMENT_EVENTS));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (!orderId.toString().equals(record.key())) {
                        continue;
                    }
                    JsonNode envelope = parse(record.value());
                    if (eventType.equals(envelope.get("eventType").asText())) {
                        return envelope;
                    }
                }
            }
        }
        return null;
    }

    /**
     * @param body a JSON body
     * @return it parsed
     */
    protected JsonNode parse(String body) {
        try {
            return json.readTree(body);
        }
        catch (Exception e) {
            throw new AssertionError("Body is not JSON: " + body, e);
        }
    }

    // ------------------------------------------------------------------ waiting helpers

    /**
     * @param timeout   how long to keep trying
     * @param condition what has to become true
     */
    protected static void awaitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError("Condition did not become true within " + timeout);
    }

    /**
     * Asserts continuously for a while — how "nothing further happened" is checked without trusting
     * one sample taken at an arbitrary moment.
     *
     * @param duration  how long the assertion must keep holding
     * @param assertion what must stay true
     */
    protected static void assertHoldsFor(Duration duration, Runnable assertion) {
        long deadline = System.nanoTime() + duration.toNanos();
        do {
            assertion.run();
            sleep(100);
        }
        while (System.nanoTime() < deadline);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ tokens

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

    /** @return a token with the USER role for a fresh random user id */
    protected static String userToken() {
        return token(UUID.randomUUID(), "USER");
    }

    /** @return a token with the ADMIN role for a fresh random user id */
    protected static String adminToken() {
        return token(UUID.randomUUID(), "ADMIN");
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
