package com.example.orders.support.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.Topics;
import com.example.orders.support.SupportIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * What happens to a record the consumer cannot handle.
 *
 * <p>Two paths, and the difference between them is the whole design:
 * <ul>
 *   <li>a handler failure <em>might</em> be transient (a locked row, a database hiccup), so it is
 *       retried with backoff and only dead-lettered when the attempts run out;</li>
 *   <li>a record that is not a message of this system will never parse, so retrying it would only
 *       park the partition — it goes to the dead letter topic on the first delivery.</li>
 * </ul>
 */
@Import(DeadLetterTopicTest.FailingListenerConfiguration.class)
class DeadLetterTopicTest extends SupportIntegrationTest {

    /** Fixed, because {@code @KafkaListener} needs a constant. */
    static final String TOPIC = "support.dlt.events";

    private static final String FAILING_KEY = "always-fails";

    private static final String POISON_KEY = "not-a-message";

    private static final String GOOD_KEY = "handled-fine";

    @Autowired
    private FailingListener listener;

    @Autowired
    private ObjectMapper json;

    @Test
    void aRecordThatKeepsFailingIsRetriedAndThenDeadLettered() {
        publish(FAILING_KEY, envelopeJson());

        ConsumerRecord<String, String> deadLettered = awaitDeadLetter(FAILING_KEY);

        // 1 delivery + messaging.consumer.retries (3) redeliveries, then the recoverer takes over.
        assertThat(listener.deliveriesOf(FAILING_KEY)).isEqualTo(4);
        // The container wraps a handler failure, so the interesting class is the cause.
        assertThat(header(deadLettered, "kafka_dlt-exception-cause-fqcn"))
                .contains(IllegalStateException.class.getName());
        assertThat(header(deadLettered, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
        // The original message is forwarded unchanged, headers included, so the dead letter is
        // replayable and still carries its correlation id.
        assertThat(header(deadLettered, EventHeaders.EVENT_TYPE)).isEqualTo("OrderCreated");
        assertThat(header(deadLettered, EventHeaders.CORRELATION_ID)).isEqualTo("corr-dlt");
    }

    @Test
    void aPoisonRecordIsDeadLetteredOnTheFirstDeliveryWithoutRetries() {
        publish(POISON_KEY, "{ this is not an envelope");

        ConsumerRecord<String, String> deadLettered = awaitDeadLetter(POISON_KEY);

        assertThat(listener.deliveriesOf(POISON_KEY)).isEqualTo(1);
        assertThat(header(deadLettered, "kafka_dlt-exception-cause-fqcn"))
                .contains(PoisonEventException.class.getName());
        assertThat(deadLettered.value()).isEqualTo("{ this is not an envelope");
    }

    @Test
    void anUnknownEventTypeIsTreatedAsPoisonRatherThanRetried() {
        String unknown = "unknown-type-" + UUID.randomUUID().toString().substring(0, 8);
        publish(unknown, "{\"eventId\":\"%s\",\"eventType\":\"SomethingNobodyDeclared\",\"payload\":{}}"
                .formatted(UUID.randomUUID()));

        ConsumerRecord<String, String> deadLettered = awaitDeadLetter(unknown);

        assertThat(listener.deliveriesOf(unknown)).isEqualTo(1);
        assertThat(header(deadLettered, "kafka_dlt-exception-message"))
                .contains("Unknown event type 'SomethingNobodyDeclared'");
    }

    @Test
    void aWellFormedRecordIsHandledOnceAndNeverDeadLettered() {
        publish(GOOD_KEY, envelopeJson());

        awaitUntil(Duration.ofSeconds(30), () -> listener.deliveriesOf(GOOD_KEY) == 1);
        assertThat(listener.handled()).contains(GOOD_KEY + ":OrderCreated");
        // Give the (non-existent) dead letter a chance to appear before claiming it did not.
        assertThat(findDeadLetter(GOOD_KEY, Duration.ofSeconds(3))).isNull();
    }

    // ------------------------------------------------------------------ the listener under test

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingListenerConfiguration {

        @Bean
        FailingListener failingListener(EventEnvelopeReader reader) {
            return new FailingListener(reader);
        }
    }

    /** Counts deliveries per key and fails on demand, so retry behaviour is observable. */
    static class FailingListener {

        private final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        private final List<String> handled = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final EventEnvelopeReader reader;

        FailingListener(EventEnvelopeReader reader) {
            this.reader = reader;
        }

        @KafkaListener(
                id = "support-dlt-test",
                topics = TOPIC,
                containerFactory = EventConsumerAutoConfiguration.CONTAINER_FACTORY)
        void onRecord(ConsumerRecord<String, String> record) {
            // Counted before parsing, so a poison record's single delivery is visible too.
            deliveries.computeIfAbsent(record.key(), key -> new AtomicInteger()).incrementAndGet();
            EventEnvelope<Object> envelope = reader.read(record);
            if (FAILING_KEY.equals(record.key())) {
                throw new IllegalStateException("this handler always fails");
            }
            handled.add(record.key() + ":" + envelope.eventType());
        }

        int deliveriesOf(String key) {
            AtomicInteger count = deliveries.get(key);
            return count == null ? 0 : count.get();
        }

        List<String> handled() {
            return List.copyOf(handled);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String envelopeJson() {
        OrderCreated payload = new OrderCreated(
                UUID.randomUUID(), "ORD-DLT", UUID.randomUUID(), new BigDecimal("1.00"), "EUR", List.of());
        EventEnvelope<OrderCreated> envelope = new EventEnvelope<>(
                UUID.randomUUID(), "OrderCreated", Instant.now(), "corr-dlt", payload);
        try {
            return json.writeValueAsString(envelope);
        }
        catch (Exception e) {
            throw new AssertionError("Could not serialise the test envelope", e);
        }
    }

    private void publish(String key, String body) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, body);
            record.headers().add(EventHeaders.EVENT_TYPE, "OrderCreated".getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.CORRELATION_ID, "corr-dlt".getBytes(StandardCharsets.UTF_8));
            if (POISON_KEY.equals(key) || key.startsWith("unknown-type-")) {
                // No usable eventType header for these: the reader has to fall back to the body and
                // then refuse it.
                record.headers().remove(EventHeaders.EVENT_TYPE);
            }
            producer.send(record).get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while producing", e);
        }
        catch (Exception e) {
            throw new AssertionError("Could not produce the test record", e);
        }
    }

    private ConsumerRecord<String, String> awaitDeadLetter(String key) {
        ConsumerRecord<String, String> found = findDeadLetter(key, Duration.ofSeconds(45));
        if (found == null) {
            throw new AssertionError("No dead letter for key " + key + " on " + Topics.deadLetterTopic(TOPIC));
        }
        return found;
    }

    private ConsumerRecord<String, String> findDeadLetter(String key, Duration timeout) {
        Properties properties = new Properties();
        properties.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-assert-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(Topics.deadLetterTopic(TOPIC)));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return null;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
