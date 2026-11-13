package com.example.orders.support;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared environment for this module's reliability tests: one real Postgres and one real Kafka.
 *
 * <p>Both are real on purpose. {@code FOR UPDATE SKIP LOCKED}, {@code JSONB}, a composite primary key
 * and dead-letter routing are exactly the things an in-memory substitute would either not support or
 * would fake convincingly enough to hide a mistake.
 *
 * <p>The containers are started once per JVM from a static initialiser, and every subclass that does
 * not add properties of its own shares one Spring context: Docker Desktop on this machine has ~4 GB.
 */
@SpringBootTest(classes = SupportTestApplication.class)
public abstract class SupportIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.2"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void supportProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.kafka.consumer.group-id", () -> "support-test");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        // All three opt-in features on, exactly as a real service enables them.
        registry.add("outbox.enabled", () -> true);
        registry.add("outbox.aggregate-type", () -> "TestAggregate");
        registry.add("idempotency.enabled", () -> true);
        registry.add("messaging.consumer.enabled", () -> true);
        // The timers are off: these tests drive the relay and the cleanup themselves, so an assertion
        // never races a background poll.
        registry.add("outbox.scheduler-enabled", () -> false);
        registry.add("idempotency.scheduler-enabled", () -> false);
    }

    /** @return a topic name no other test uses, so records never cross over */
    protected static String uniqueTopic(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Polls until the condition holds, instead of sleeping for a worst case.
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
}
