package com.example.orders.support.messaging;

import com.example.orders.common.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.StringOrBytesSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Opt-in wiring of the event listeners: {@code messaging.consumer.enabled=true}.
 *
 * <p>Three decisions are made here once for every service:
 *
 * <p><b>Records are strings.</b> The value deserialiser cannot fail, so a broken message is not a
 * container-level deserialisation problem but a business-level one that
 * {@link EventEnvelopeReader} classifies and the error handler dead-letters.
 *
 * <p><b>Retry, then dead-letter.</b> A handler failure is retried with exponential backoff
 * ({@code messaging.consumer.*}); when the attempts run out the record goes to
 * {@code <topic>.DLT} with the failure recorded in its headers, and the partition moves on. A
 * {@link PoisonEventException} skips the retries entirely, because nothing about the record can
 * change between attempts.
 *
 * <p><b>Offsets follow the work.</b> {@code AckMode.RECORD} commits after each record has either
 * been handled or been dead-lettered — never before — so a crash costs a redelivery (which the
 * {@code processed_events} log absorbs) and never a lost message.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, ConcurrentKafkaListenerContainerFactory.class})
@ConditionalOnProperty(name = "messaging.consumer.enabled", havingValue = "true")
@EnableConfigurationProperties(EventConsumerProperties.class)
@EnableKafka
public class EventConsumerAutoConfiguration {

    /** Name of the container factory every {@code @KafkaListener} in this system refers to. */
    public static final String CONTAINER_FACTORY = "eventListenerContainerFactory";

    /**
     * @param json the application's mapper
     * @return the typed envelope reader shared by every listener
     */
    @Bean
    @ConditionalOnMissingBean
    public EventEnvelopeReader eventEnvelopeReader(ObjectMapper json) {
        return new EventEnvelopeReader(json);
    }

    /**
     * The producer the dead-letter recoverer publishes with. {@code acks=all} and an idempotent
     * producer, like the outbox: a dead letter that is itself lost would turn a visible failure into
     * a silent one.
     *
     * @param properties {@code spring.kafka.*}
     * @param sslBundles TLS material, when any is configured
     * @return a producer that can write both string and raw byte payloads
     */
    @Bean
    @ConditionalOnMissingBean(name = "deadLetterKafkaTemplate")
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate(
            KafkaProperties properties, ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry) {
        Map<String, Object> config = new HashMap<>(
                properties.buildProducerProperties(sslBundles.getIfAvailable()));
        // StringOrBytes: the record may arrive as a String, or as raw bytes when it could not be
        // deserialised at all. Both have to reach the dead letter topic unchanged.
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringOrBytesSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringOrBytesSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    /**
     * @param deadLetterKafkaTemplate producer for {@code <topic>.DLT}
     * @param properties              retry settings
     * @return retry-then-dead-letter handler used by every listener container
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler eventErrorHandler(
            KafkaTemplate<Object, Object> deadLetterKafkaTemplate, EventConsumerProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                // Partition -1: let the producer place the record. The dead letter topic is created
                // on demand and does not have to mirror the source topic's partition count.
                (record, exception) -> new TopicPartition(Topics.deadLetterTopic(record.topic()), -1));

        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(properties.getInitialBackoffMs());
        backOff.setMultiplier(properties.getBackoffMultiplier());
        backOff.setMaxInterval(properties.getMaxBackoffMs());
        // Attempts, not elapsed time: the number of redeliveries has to be predictable, and the
        // default DEFAULT_MAX_ELAPSED_TIME would keep a failing record in the loop for far longer.
        backOff.setMaxAttempts(Math.max(0, properties.getRetries()));
        backOff.setMaxElapsedTime(Long.MAX_VALUE);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Nothing about these will be different on a second attempt, so do not spend the backoff on
        // them: straight to the dead letter topic. (Deserialisation and conversion failures are in
        // DefaultErrorHandler's fatal list already.)
        handler.addNotRetryableExceptions(PoisonEventException.class);
        return handler;
    }

    /**
     * @param properties {@code spring.kafka.*}
     * @param sslBundles TLS material, when any is configured
     * @return a consumer factory reading keys and values as plain strings
     */
    @Bean
    @ConditionalOnMissingBean(name = "eventConsumerFactory")
    public ConsumerFactory<String, String> eventConsumerFactory(
            KafkaProperties properties, ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry) {
        Map<String, Object> config = new HashMap<>(
                properties.buildConsumerProperties(sslBundles.getIfAvailable()));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.remove("spring.deserializer.value.delegate.class");
        config.remove("spring.deserializer.key.delegate.class");
        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(config);
        // Exposes the Kafka client metrics (records lag, fetch rate, ...) to Prometheus.
        meterRegistry.ifAvailable(registry ->
                factory.addListener(new org.springframework.kafka.core.MicrometerConsumerListener<>(registry)));
        return factory;
    }

    /**
     * @param eventConsumerFactory the string consumer factory
     * @param eventErrorHandler    retry-then-dead-letter handler
     * @param properties           concurrency setting
     * @return the container factory referenced by {@code @KafkaListener(containerFactory = ...)}
     */
    @Bean(CONTAINER_FACTORY)
    @ConditionalOnMissingBean(name = CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, String> eventListenerContainerFactory(
            ConsumerFactory<String, String> eventConsumerFactory,
            DefaultErrorHandler eventErrorHandler,
            EventConsumerProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(eventConsumerFactory);
        factory.setConcurrency(properties.getConcurrency());
        factory.setCommonErrorHandler(eventErrorHandler);
        factory.setRecordInterceptor(new CorrelationIdRecordInterceptor());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Each consumed record becomes a span; its parent comes from the traceparent header the outbox
        // relay sets, so one trace follows an order across services.
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
