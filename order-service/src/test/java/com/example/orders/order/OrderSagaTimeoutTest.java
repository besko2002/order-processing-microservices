package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.EventHeaders;
import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.support.outbox.OutboxEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The saga's deadline.
 *
 * <p>An orchestrated saga has one failure mode that no amount of retrying fixes: a step that never
 * answers. The sweeper is what keeps such an order from sitting in a non-final state forever — and,
 * worse, from keeping its stock reserved forever.
 *
 * <p>The timeout is shortened to a second here and the sweep runs every 300 ms, so the test observes
 * the real scheduled path instead of calling the sweeper by hand. That needs its own Spring context,
 * which is why this is a separate class: every other order-service test needs orders to stay where
 * they are.
 */
@TestPropertySource(properties = {
        "orders.saga.timeout=PT1S",
        "orders.saga.sweep-interval-ms=300"
})
// The context is torn down with the class: its sweeper would otherwise keep running on the shared
// database and cancel the orders of every test class that comes after it.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderSagaTimeoutTest extends OrderIntegrationTest {

    @Test
    void anOrderNobodyAnswersIsEventuallyCancelledWithItsStockReleased() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID());

        awaitUntil(Duration.ofSeconds(45), () -> orders.findById(orderId)
                .map(order -> order.getStatus() == OrderStatus.CANCELLED)
                .orElse(false));

        Order order = orders.findById(orderId).orElseThrow();
        assertThat(order.getFailureReason())
                .contains("did not complete within")
                .contains("PENDING");
        // Compensated, not just given up on: the release goes out even though this service never
        // heard that a reservation was taken, because it cannot know that it was not.
        assertThat(types(orderId)).contains("ReleaseStock", "OrderCancelled");
        assertThat(types(orderId)).doesNotContain("RefundPayment");
    }

    @Test
    void anOrderWhosePaymentNeverAnswersIsCancelledAndItsStockReleased() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID());
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-timeout-paying",
                new StockReserved(orderId, UUID.randomUUID(), List.of())));

        awaitUntil(Duration.ofSeconds(45), () -> orders.findById(orderId)
                .map(order -> order.getStatus() == OrderStatus.CANCELLED)
                .orElse(false));

        assertThat(orders.findById(orderId).orElseThrow().getFailureReason())
                .contains("did not complete within");
        assertThat(types(orderId)).contains("ReleaseStock", "OrderCancelled");
    }

    @Test
    void aConfirmedOrderIsNeverTouchedBySweeping() throws Exception {
        UUID orderId = placeOrder(UUID.randomUUID());
        publish(Topics.INVENTORY_EVENTS, orderId, EventEnvelope.of("corr-timeout-confirmed",
                new StockReserved(orderId, UUID.randomUUID(), List.of())));
        awaitUntil(Duration.ofSeconds(45), () -> orders.findById(orderId)
                .map(order -> order.getStatus() == OrderStatus.PAYMENT_PENDING)
                .orElse(false));
        publish(Topics.PAYMENT_EVENTS, orderId, EventEnvelope.of("corr-timeout-confirmed",
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("149.00"), "EUR")));
        awaitUntil(Duration.ofSeconds(45), () -> orders.findById(orderId)
                .map(order -> order.getStatus() == OrderStatus.CONFIRMED)
                .orElse(false));

        // CONFIRMED is final: several sweeps must pass over it without doing anything.
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            Thread.sleep(100);
        }
        assertThat(types(orderId)).doesNotContain("ReleaseStock");
    }

    // ------------------------------------------------------------------ helpers

    private UUID placeOrder(UUID user) throws Exception {
        UUID product = givenCatalogProduct(
                "SKU-TIMEOUT-" + UUID.randomUUID().toString().substring(0, 6), "Slow product", "149.00");
        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, 1)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(parse(body).get("id").asText());
    }

    private List<String> types(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .map(OutboxEvent::getEventType)
                .toList();
    }

    private void publish(String topic, UUID key, EventEnvelope<?> envelope) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic, key.toString(), json.writeValueAsString(envelope));
            record.headers().add(EventHeaders.EVENT_ID,
                    envelope.eventId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE,
                    envelope.eventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.CORRELATION_ID,
                    envelope.correlationId().getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while producing", e);
        }
        catch (Exception e) {
            throw new AssertionError("Could not produce onto " + topic, e);
        }
    }
}
