package com.example.orders.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeJacksonTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void roundTripsAnOrderCreatedEnvelope() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderCreated payload = new OrderCreated(
                orderId,
                "ORD-1001",
                UUID.randomUUID(),
                new BigDecimal("129.98"),
                "EUR",
                List.of(new OrderItem(UUID.randomUUID(), "SKU-1", 2, new BigDecimal("64.99"))));

        EventEnvelope<OrderCreated> original = new EventEnvelope<>(
                UUID.randomUUID(),
                "OrderCreated",
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                "corr-42",
                payload);

        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"eventType\":\"OrderCreated\"", "\"correlationId\":\"corr-42\"", "ORD-1001");

        EventEnvelope<OrderCreated> restored =
                mapper.readValue(json, new TypeReference<EventEnvelope<OrderCreated>>() {});

        assertThat(restored).isEqualTo(original);
        assertThat(restored.payload().items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.sku()).isEqualTo("SKU-1");
                    assertThat(item.quantity()).isEqualTo(2);
                    assertThat(item.unitPrice()).isEqualByComparingTo("64.99");
                });
    }

    @Test
    void factoryDerivesEventTypeFromPayload() throws Exception {
        PaymentFailed payload = new PaymentFailed(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "EUR", "INSUFFICIENT_FUNDS");

        EventEnvelope<PaymentFailed> envelope = EventEnvelope.of("corr-7", payload);
        assertThat(envelope.eventType()).isEqualTo("PaymentFailed");
        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.occurredAt()).isNotNull();

        EventEnvelope<PaymentFailed> restored = mapper.readValue(
                mapper.writeValueAsString(envelope), new TypeReference<EventEnvelope<PaymentFailed>>() {});
        assertThat(restored.payload()).isEqualTo(payload);
    }

    @Test
    void deadLetterTopicNamesAreDerivedFromTopics() {
        assertThat(Topics.deadLetterTopic(Topics.ORDER_EVENTS)).isEqualTo("order.events.DLT");
        assertThat(Topics.INVENTORY_COMMANDS).isEqualTo("inventory.commands");
    }
}
