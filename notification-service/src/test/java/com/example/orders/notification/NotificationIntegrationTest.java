package com.example.orders.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.OrderCancelled;
import com.example.orders.common.events.OrderConfirmed;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.OrderRejected;
import com.example.orders.common.events.Topics;
import com.example.orders.notification.domain.Notification;
import com.example.orders.notification.domain.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {"AUTH_JWKS_URI=http://localhost:1/unused", "management.health.mail.enabled=false", "spring.kafka.consumer.properties.metadata.max.age.ms=1000"})
@AutoConfigureMockMvc
@Testcontainers
class NotificationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.2"));

    @org.springframework.test.context.DynamicPropertySource
    static void kafka(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    NotificationRepository repository;

    @Autowired
    ObjectMapper json;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void confirmedOrderSendsOneEmailAndIsStored() {
        UUID customer = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        publish(order, EventEnvelope.of("OrderConfirmed", "corr-n1",
                new OrderConfirmed(order, "ORD-1", customer, new BigDecimal("99.90"), "EUR")));

        List<Notification> rows = awaitRows(customer, 1);
        Notification n = rows.get(0);
        assertThat(n.getType()).isEqualTo(Notification.Type.ORDER_CONFIRMED);
        assertThat(n.getStatus()).isEqualTo(Notification.Status.SENT);
        assertThat(n.getRecipient()).isEqualTo(customer + "@customers.orders.local");
        assertThat(n.getBody()).contains("99.90 EUR");
        verify(mailSender, timeout(5000)).send(any(SimpleMailMessage.class));
    }

    @Test
    void duplicateDeliveryNotifiesOnce() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        EventEnvelope<OrderCancelled> envelope = EventEnvelope.of("OrderCancelled", "corr-n2",
                new OrderCancelled(order, "ORD-2", customer, "Payment declined"));
        publish(order, envelope);
        publish(order, envelope);

        awaitRows(customer, 1);
        Thread.sleep(2000);
        assertThat(repository.findAll()).filteredOn(r -> r.getCustomerId().equals(customer)).hasSize(1);
    }

    @Test
    void rejectedOrderIsNotifiedAndOtherEventsAreIgnored() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID ignored = UUID.randomUUID();
        publish(ignored, EventEnvelope.of("OrderCreated", "corr-n3",
                new OrderCreated(ignored, "ORD-3", customer, new BigDecimal("1.00"), "EUR", List.of())));
        UUID order = UUID.randomUUID();
        publish(order, EventEnvelope.of("OrderRejected", "corr-n3",
                new OrderRejected(order, "ORD-4", customer, "Insufficient stock")));

        List<Notification> rows = awaitRows(customer, 1);
        assertThat(rows.get(0).getOrderId()).isEqualTo(order);
        assertThat(rows.get(0).getType()).isEqualTo(Notification.Type.ORDER_REJECTED);
    }

    @Test
    void historyIsPrivateToTheCaller() throws Exception {
        UUID me = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        UUID o1 = UUID.randomUUID();
        UUID o2 = UUID.randomUUID();
        publish(o1, EventEnvelope.of("OrderConfirmed", "c", new OrderConfirmed(o1, "ORD-5", me, BigDecimal.TEN, "EUR")));
        publish(o2, EventEnvelope.of("OrderConfirmed", "c", new OrderConfirmed(o2, "ORD-6", someoneElse, BigDecimal.TEN, "EUR")));
        awaitRows(me, 1);
        awaitRows(someoneElse, 1);

        mvc.perform(get("/api/notifications/me").with(jwt().jwt(j -> j.subject(me.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(o1.toString()))
                // The mail went to a local sink, not to a real inbox - the response says so.
                .andExpect(jsonPath("$.content[0].simulated").value(true))
                .andExpect(jsonPath("$.content[0].recipient").value(me + "@customers.orders.local"));
        mvc.perform(get("/api/notifications/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void mailIsNotSentForIgnoredEvents() {
        UUID order = UUID.randomUUID();
        publish(order, EventEnvelope.of("OrderCreated", "c",
                new OrderCreated(order, "ORD-7", UUID.randomUUID(), BigDecimal.ONE, "EUR", List.of())));
        verify(mailSender, after(1500).never()).send(any(SimpleMailMessage.class));
    }

    private static org.mockito.verification.VerificationAfterDelay after(long ms) {
        return org.mockito.Mockito.after(ms);
    }

    private List<Notification> awaitRows(UUID customer, int count) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            List<Notification> rows = repository.findAll().stream()
                    .filter(r -> r.getCustomerId().equals(customer)).toList();
            if (rows.size() >= count) {
                return rows;
            }
            try {
                Thread.sleep(200);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Expected " + count + " notification(s) for " + customer);
    }

    private void publish(UUID key, EventEnvelope<?> envelope) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    Topics.ORDER_EVENTS, key.toString(), json.writeValueAsString(envelope));
            record.headers().add(EventHeaders.EVENT_ID, envelope.eventId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE, envelope.eventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.CORRELATION_ID, envelope.correlationId().getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();
        }
        catch (Exception e) {
            throw new AssertionError("Could not publish", e);
        }
    }
}
