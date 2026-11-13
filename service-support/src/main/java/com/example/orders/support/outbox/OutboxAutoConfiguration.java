package com.example.orders.support.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Opt-in wiring of the transactional outbox: {@code outbox.enabled=true} and a Flyway migration
 * creating {@code outbox_events} are all a service needs.
 *
 * <p>The mapping lives in this module, the table lives in the service's own database — the two
 * halves of "database per service" that a shared library has to respect. The entity and the
 * repository are picked up because each service lists this module's packages in its
 * {@code @EntityScan} / {@code @EnableJpaRepositories}; making that explicit in the services is
 * deliberate, an auto-configuration that silently rewrote their persistence scanning would be far
 * harder to reason about.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, EntityManagerFactory.class})
@ConditionalOnProperty(name = "outbox.enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    /**
     * The producer the relay publishes with.
     *
     * <p>It serialises strings, not objects: the envelope was already serialised inside the write
     * transaction and stored in {@code outbox_events.payload}, so the relay ships exactly those
     * bytes. Re-serialising here would mean the message on the topic is not provably the message
     * that was committed.
     *
     * <p>Two settings carry the delivery guarantee. {@code acks=all} means the leader only
     * acknowledges once the in-sync replicas have the record, so a broker failover cannot swallow
     * an event we have already marked published. {@code enable.idempotence=true} means the broker
     * de-duplicates the producer's own internal retries, so a retried send does not put the same
     * event on the topic twice.
     *
     * <p>The timeouts are short on purpose: this producer runs inside a database transaction that
     * holds row locks, so an unreachable broker has to fail fast rather than block the relay for
     * the client's two-minute default.
     *
     * @param properties {@code spring.kafka.*}, including the bootstrap servers
     * @param sslBundles TLS material, when any is configured
     * @param outbox     the outbox settings
     * @return a string producer configured for at-least-once delivery without duplicates from the
     *         client's own retries
     */
    @Bean
    @ConditionalOnMissingBean(name = "outboxKafkaTemplate")
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            KafkaProperties properties, ObjectProvider<SslBundles> sslBundles, OutboxProperties outbox) {
        Map<String, Object> config = new HashMap<>(
                properties.buildProducerProperties(sslBundles.getIfAvailable()));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, outbox.getProducer().getMaxBlockMs());
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, outbox.getProducer().getRequestTimeoutMs());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, outbox.getProducer().getDeliveryTimeoutMs());
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    /**
     * @param repository outbox rows
     * @param json       the application's mapper
     * @param outbox     the outbox settings
     * @return the writer business code calls inside its own transaction
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(
            OutboxRepository repository, ObjectMapper json, OutboxProperties outbox) {
        return new OutboxWriter(repository, json, outbox.getAggregateType());
    }

    /**
     * @param repository          outbox rows
     * @param outboxKafkaTemplate the relay's producer
     * @param outbox              the outbox settings
     * @return the relay, callable directly by tests and by the scheduler
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> outboxKafkaTemplate,
            OutboxProperties outbox) {
        return new OutboxRelay(
                repository,
                outboxKafkaTemplate,
                outbox.getBatchSize(),
                outbox.getSendTimeout(),
                outbox.getRetention());
    }

    /** The timer, switched off by tests that want to drive the relay themselves. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "outbox.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    public static class SchedulerConfiguration {

        /**
         * @param relay the relay to tick
         * @return the scheduled poller
         */
        @Bean
        @ConditionalOnMissingBean
        public OutboxRelayScheduler outboxRelayScheduler(OutboxRelay relay) {
            return new OutboxRelayScheduler(relay);
        }
    }
}
