package com.example.orders.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reading orders, and who is allowed to.
 *
 * <p>The interesting case is somebody else's order: it answers {@code 404}, not {@code 403},
 * because a {@code 403} would confirm the id exists and turn the endpoint into an oracle for
 * enumerating order ids.
 */
class OrderQueryApiTest extends OrderIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private UUID placeOrder(UUID user, UUID product, int quantity) throws Exception {
        String body = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token(user, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(product, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(parse(body).get("id").asText());
    }

    // ------------------------------------------------------------------ GET /api/orders/{id}

    @Test
    void theOwnerReadsTheirOwnOrder() throws Exception {
        UUID product = givenCatalogProduct("SKU-R1", "Reader one", "11.00");
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, product, 2);

        mvc.perform(get("/api/orders/" + orderId)
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(22.00))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void anotherUsersOrderIs404SoIdsDoNotLeak() throws Exception {
        UUID product = givenCatalogProduct("SKU-R2", "Reader two", "11.00");
        UUID orderId = placeOrder(UUID.randomUUID(), product, 1);

        mvc.perform(get("/api/orders/" + orderId)
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Order " + orderId + " does not exist"));
    }

    @Test
    void anAdminReadsAnyOrder() throws Exception {
        UUID product = givenCatalogProduct("SKU-R3", "Reader three", "11.00");
        UUID orderId = placeOrder(UUID.randomUUID(), product, 1);

        mvc.perform(get("/api/orders/" + orderId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()));
    }

    @Test
    void anUnknownOrderIs404() throws Exception {
        mvc.perform(get("/api/orders/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnparsableOrderIdIs400() throws Exception {
        mvc.perform(get("/api/orders/not-a-uuid")
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.id").isNotEmpty());
    }

    @Test
    void readingAnOrderWithoutATokenIs401() throws Exception {
        mvc.perform(get("/api/orders/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // ------------------------------------------------------------------ GET /api/orders/me

    @Test
    void myOrdersAreListedNewestFirstAndOnlyMine() throws Exception {
        UUID product = givenCatalogProduct("SKU-ME", "Mine", "10.00");
        UUID me = UUID.randomUUID();
        UUID first = placeOrder(me, product, 1);
        UUID second = placeOrder(me, product, 2);
        placeOrder(UUID.randomUUID(), product, 3);

        mvc.perform(get("/api/orders/me")
                        .header("Authorization", "Bearer " + token(me, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second.toString()))
                .andExpect(jsonPath("$.content[1].id").value(first.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void myOrdersPageAndClampTheirSize() throws Exception {
        UUID product = givenCatalogProduct("SKU-PAGE", "Paged", "10.00");
        UUID me = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            placeOrder(me, product, i + 1);
        }

        mvc.perform(get("/api/orders/me").param("size", "2")
                        .header("Authorization", "Bearer " + token(me, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(false));

        mvc.perform(get("/api/orders/me").param("size", "5000")
                        .header("Authorization", "Bearer " + token(me, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void myOrdersNeedAToken() throws Exception {
        mvc.perform(get("/api/orders/me"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ GET /api/orders (ADMIN)

    @Test
    void anAdminListsEveryUsersOrders() throws Exception {
        UUID product = givenCatalogProduct("SKU-ALL", "All", "10.00");
        placeOrder(UUID.randomUUID(), product, 1);
        placeOrder(UUID.randomUUID(), product, 2);

        mvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void theAdminListFiltersByStatus() throws Exception {
        UUID product = givenCatalogProduct("SKU-ST", "Status", "10.00");
        placeOrder(UUID.randomUUID(), product, 1);

        mvc.perform(get("/api/orders").param("status", "PENDING")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/orders").param("status", "CONFIRMED")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(get("/api/orders").param("status", "NONSENSE")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPlainUserMayNotListEveryOrder() throws Exception {
        mvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void theAdminListNeedsAToken() throws Exception {
        mvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ GET /api/orders/{id}/events

    @Test
    void theOwnerReadsTheEventsOfTheirOrderOldestFirstWithTheTraceId() throws Exception {
        UUID product = givenCatalogProduct("SKU-EV1", "Events one", "11.00");
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, product, 1);

        mvc.perform(get("/api/orders/" + orderId + "/events")
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventType").value("OrderCreated"))
                .andExpect(jsonPath("$[0].topic").value("order.events"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].attempts").isNumber())
                .andExpect(jsonPath("$[0].payload").doesNotExist());
    }

    @Test
    void anEventRowCarriesTheTraceIdParsedFromItsTraceparent() throws Exception {
        UUID product = givenCatalogProduct("SKU-EV2", "Events two", "11.00");
        UUID user = UUID.randomUUID();
        UUID orderId = placeOrder(user, product, 1);
        // traceparent is written once by the outbox writer and never updated through JPA, so the
        // test stamps the row directly, as the writer would have done inside the transaction.
        jdbc.update("UPDATE outbox_events SET traceparent = ? WHERE aggregate_id = ?",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", orderId);

        mvc.perform(get("/api/orders/" + orderId + "/events")
                        .header("Authorization", "Bearer " + token(user, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].traceId").value("4bf92f3577b34da6a3ce929d0e0e4736"));
    }

    @Test
    void anAdminReadsTheEventsOfAnyOrder() throws Exception {
        UUID product = givenCatalogProduct("SKU-EV3", "Events three", "11.00");
        UUID orderId = placeOrder(UUID.randomUUID(), product, 1);

        mvc.perform(get("/api/orders/" + orderId + "/events")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("OrderCreated"));
    }

    @Test
    void anotherUsersOrderEventsAre404() throws Exception {
        UUID product = givenCatalogProduct("SKU-EV4", "Events four", "11.00");
        UUID orderId = placeOrder(UUID.randomUUID(), product, 1);

        mvc.perform(get("/api/orders/" + orderId + "/events")
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void theEventsOfAnUnknownOrderAre404() throws Exception {
        mvc.perform(get("/api/orders/" + UUID.randomUUID() + "/events")
                        .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void readingOrderEventsWithoutATokenIs401() throws Exception {
        mvc.perform(get("/api/orders/" + UUID.randomUUID() + "/events"))
                .andExpect(status().isUnauthorized());
    }
}
