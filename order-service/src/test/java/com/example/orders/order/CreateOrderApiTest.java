package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.Topics;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.support.outbox.OutboxEvent;
import com.example.orders.support.correlation.CorrelationId;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** {@code POST /api/orders}: pricing, atomicity, validation and idempotency. */
class CreateOrderApiTest extends OrderIntegrationTest {

    @Test
    void anOrderIsPricedFromTheCatalogNotFromTheRequest() throws Exception {
        UUID keyboard = givenCatalogProduct("SKU-KB", "Alpha keyboard", "49.90");
        UUID mouse = givenCatalogProduct("SKU-MS", "Beta mouse", "19.95");
        UUID user = UUID.randomUUID();

        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(keyboard, 2, mouse, 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.userId").value(user.toString()))
                .andExpect(jsonPath("$.currency").value("EUR"))
                // 2 * 49.90 + 3 * 19.95 = 99.80 + 59.85
                .andExpect(jsonPath("$.totalAmount").value(159.65))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.orderNumber").value(org.hamcrest.Matchers.startsWith("ORD-")))
                .andReturn().getResponse().getContentAsString();

        JsonNode created = parse(body);
        assertThat(created.get("id").asText()).isNotBlank();

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(keyboard, 1)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.startsWith("/api/orders/")));
    }

    @Test
    void theOrderItsLinesAndItsOutboxRowAreWrittenTogether() throws Exception {
        UUID desk = givenCatalogProduct("SKU-DESK", "Standing desk", "399.00");
        UUID user = UUID.randomUUID();

        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(desk, 2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(parse(body).get("id").asText());

        Order stored = orders.findWithItemsById(orderId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(stored.getTotalAmount()).isEqualByComparingTo("798.00");
        assertThat(stored.getItems()).singleElement().satisfies(line -> {
            assertThat(line.getProductId()).isEqualTo(desk);
            assertThat(line.getProductName()).isEqualTo("Standing desk");
            assertThat(line.getUnitPrice()).isEqualByComparingTo("399.00");
            assertThat(line.getQuantity()).isEqualTo(2);
            assertThat(line.getLineTotal()).isEqualByComparingTo("798.00");
        });

        List<OutboxEvent> events = outbox.findByAggregateIdOrderByCreatedAtAsc(orderId);
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getEventType()).isEqualTo("OrderCreated");
        assertThat(event.getTopic()).isEqualTo(Topics.ORDER_EVENTS);

        JsonNode envelope = parse(event.getPayload());
        // The row id IS the event id: that is what consumers de-duplicate on.
        assertThat(envelope.get("eventId").asText()).isEqualTo(event.getId().toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("OrderCreated");
        assertThat(envelope.get("occurredAt").asText()).isNotBlank();
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(payload.get("customerId").asText()).isEqualTo(user.toString());
        assertThat(payload.get("totalAmount").asDouble()).isEqualTo(798.00);
        assertThat(payload.get("currency").asText()).isEqualTo("EUR");
        assertThat(payload.get("items").get(0).get("sku").asText()).isEqualTo("SKU-DESK");
        assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(payload.get("items").get(0).get("unitPrice").asDouble()).isEqualTo(399.00);
    }

    @Test
    void whenTheOrderCannotBeSavedNoOutboxRowSurvivesEither() throws Exception {
        // product_name is VARCHAR(200); a longer name makes the INSERT of the line fail, which is
        // the cleanest way to prove the outbox row shares the order's transaction.
        UUID poison = givenCatalogProduct("SKU-LONG", "N".repeat(300), "10.00");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(poison, 1)))
                .andExpect(status().isConflict());

        assertThat(orders.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void anUnknownProductIs400NamingIt() throws Exception {
        UUID unknown = UUID.randomUUID();

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(unknown, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Unknown product: " + unknown));

        assertThat(orders.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void repeatedProductIdsAreMergedIntoOneLine() throws Exception {
        UUID cable = givenCatalogProduct("SKU-CBL", "USB cable", "5.00");
        UUID user = UUID.randomUUID();

        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(cable, 2, cable, 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andReturn().getResponse().getContentAsString();

        UUID orderId = UUID.fromString(parse(body).get("id").asText());
        assertThat(orders.findWithItemsById(orderId).orElseThrow().getItems()).hasSize(1);
        // One distinct product means one catalog call, not two.
        assertThat(CATALOG_CORRELATION_IDS).hasSize(1);
    }

    @Test
    void mergedQuantitiesStillRespectThePerProductMaximum() throws Exception {
        UUID cable = givenCatalogProduct("SKU-CBL2", "USB cable", "5.00");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(cable, 60, cable, 60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("adds up to 120")));
    }

    // ------------------------------------------------------------------ validation

    @Test
    void anEmptyItemListIs400() throws Exception {
        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.items").isNotEmpty());
    }

    @Test
    void aZeroQuantityIs400() throws Exception {
        UUID product = givenCatalogProduct("SKU-Z", "Zero", "1.00");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['items[0].quantity']").value("must be at least 1"));
    }

    @Test
    void aQuantityAboveTheMaximumIs400() throws Exception {
        UUID product = givenCatalogProduct("SKU-BIG", "Big", "1.00");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, 101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['items[0].quantity']").value("must be at most 100"));
    }

    @Test
    void moreThanTwentyLinesIs400() throws Exception {
        Object[] lines = new Object[21 * 2];
        for (int i = 0; i < 21; i++) {
            lines[i * 2] = UUID.randomUUID();
            lines[i * 2 + 1] = 1;
        }

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(lines)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.items").value("must contain at most 20 items"));
        // Validation runs before anything is priced.
        assertThat(CATALOG_CORRELATION_IDS).isEmpty();
    }

    @Test
    void aMalformedBodyIs400() throws Exception {
        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": nope}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or missing request body"));
    }

    @Test
    void anUnauthenticatedCallerCannotPlaceAnOrder() throws Exception {
        UUID product = givenCatalogProduct("SKU-NOAUTH", "Nope", "1.00");

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, 1)))
                .andExpect(status().isUnauthorized());

        assertThat(orders.count()).isZero();
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void theSameIdempotencyKeyWithTheSameItemsReplaysTheOrder() throws Exception {
        UUID lamp = givenCatalogProduct("SKU-LAMP", "Desk lamp", "29.50");
        UUID user = UUID.randomUUID();
        String key = "checkout-" + UUID.randomUUID();

        String first = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(lamp, 2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(lamp, 2)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(parse(second).get("id").asText()).isEqualTo(parse(first).get("id").asText());
        assertThat(orders.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void theSameIdempotencyKeyWithDifferentItemsIs409() throws Exception {
        UUID lamp = givenCatalogProduct("SKU-LAMP2", "Desk lamp", "29.50");
        UUID user = UUID.randomUUID();
        String key = "checkout-" + UUID.randomUUID();

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(lamp, 2)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(lamp, 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("already used for a different order")));

        assertThat(orders.count()).isEqualTo(1);
    }

    @Test
    void twoUsersMayPickTheSameIdempotencyKey() throws Exception {
        UUID lamp = givenCatalogProduct("SKU-LAMP3", "Desk lamp", "10.00");
        String key = "shared-key";

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(UUID.randomUUID(), "USER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(lamp, 1)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(UUID.randomUUID(), "USER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(lamp, 1)))
                .andExpect(status().isCreated());

        assertThat(orders.count()).isEqualTo(2);
    }

    @Test
    void anOverlongIdempotencyKeyIs400() throws Exception {
        UUID lamp = givenCatalogProduct("SKU-LAMP4", "Desk lamp", "10.00");

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .header("Idempotency-Key", "k".repeat(101))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(lamp, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("at most 100 characters")));
    }

    // ------------------------------------------------------------------ correlation id

    @Test
    void theRequestsCorrelationIdIsForwardedAndStoredOnTheOutboxRow() throws Exception {
        UUID book = givenCatalogProduct("SKU-BOOK", "A book", "12.00");
        String correlationId = "corr-" + UUID.randomUUID();

        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .header(CorrelationId.HEADER, correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(book, 1)))
                .andExpect(status().isCreated())
                .andExpect(header().string(CorrelationId.HEADER, correlationId))
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(parse(body).get("id").asText());

        // Forwarded to inventory-service ...
        assertThat(CATALOG_CORRELATION_IDS).containsExactly(correlationId);
        // ... and recorded on the event, so the saga can be traced back to this request.
        OutboxEvent event = outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).getFirst();
        assertThat(event.getCorrelationId()).isEqualTo(correlationId);
        assertThat(parse(event.getPayload()).get("correlationId").asText()).isEqualTo(correlationId);
    }

    @Test
    void aCatalog5xxIsNotMistakenForAnUnknownProduct() throws Exception {
        UUID product = UUID.randomUUID();
        catalogAnswers(id -> new MockResponse().setResponseCode(500));

        mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, 1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }
}
