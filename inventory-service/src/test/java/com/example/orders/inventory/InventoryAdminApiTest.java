package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.OrderItem;
import com.example.orders.inventory.domain.Product;
import com.example.orders.support.correlation.CorrelationId;
import com.example.orders.support.error.RestAccessDeniedHandler;
import com.example.orders.support.error.RestAuthenticationEntryPoint;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The administrative half of the API. Access control is asserted at this service, not at the
 * gateway: that is the whole point of validating the token twice.
 */
class InventoryAdminApiTest extends InventoryIntegrationTest {

    // ------------------------------------------------------------------ access control

    @Test
    void creatingAProductWithoutATokenIs401() throws Exception {
        mvc.perform(post("/api/inventory/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("SKU-401", "Denied", "9.99", 1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value(RestAuthenticationEntryPoint.MESSAGE))
                .andExpect(jsonPath("$.path").value("/api/inventory/products"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());

        assertThat(products.count()).isZero();
    }

    @Test
    void creatingAProductAsAPlainUserIs403() throws Exception {
        mvc.perform(post("/api/inventory/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("SKU-403", "Denied", "9.99", 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(RestAccessDeniedHandler.MESSAGE));

        assertThat(products.count()).isZero();
    }

    @Test
    void aTokenFromAnUnknownKeyOrIssuerIs401() throws Exception {
        for (String token : List.of(tokenSignedByAnotherKey(), tokenFromAnotherIssuer(), expiredAdminToken())) {
            mvc.perform(get("/api/inventory/reservations")
                            .param("orderId", UUID.randomUUID().toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/api/inventory/reservations")
                        .param("orderId", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ create

    @Test
    void adminCreatesAProductAndGetsALocation() throws Exception {
        var response = mvc.perform(post("/api/inventory/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .header(CorrelationId.HEADER, "admin-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("SKU-NEW-1", "Brand new thing", "1234.50", 12)))
                .andExpect(status().isCreated())
                .andExpect(header().string(CorrelationId.HEADER, "admin-create-1"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.sku").value("SKU-NEW-1"))
                .andExpect(jsonPath("$.name").value("Brand new thing"))
                .andExpect(jsonPath("$.price").value(1234.50))
                .andExpect(jsonPath("$.availableQuantity").value(12))
                .andExpect(jsonPath("$.reservedQuantity").value(0))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(header().exists("Location"))
                .andReturn()
                .getResponse();

        String id = json.readTree(response.getContentAsString()).get("id").asText();
        // The Location points at the public catalog entry, which is where the product is readable.
        assertThat(response.getHeader("Location")).isEqualTo("/api/products/" + id);
        assertThat(products.findBySku("SKU-NEW-1")).isPresent();

        mvc.perform(get("/api/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-NEW-1"));
    }

    @Test
    void anInvalidProductIs400WithOneEntryPerBrokenField() throws Exception {
        mvc.perform(post("/api/inventory/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"","name":"","price":-5,"availableQuantity":-1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.sku").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.name").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.price").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.availableQuantity").isNotEmpty());

        assertThat(products.count()).isZero();
    }

    @Test
    void aMissingPriceIsReportedAsAFieldError() throws Exception {
        mvc.perform(post("/api/inventory/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU-NO-PRICE","name":"No price","availableQuantity":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.price").value("must be provided"));
    }

    @Test
    void aDuplicateSkuIs409() throws Exception {
        givenProduct("SKU-DUP", 3);

        mvc.perform(post("/api/inventory/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("SKU-DUP", "Second attempt", "5.00", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("SKU is already in use: SKU-DUP"));

        assertThat(products.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ update

    @Test
    void adminUpdatesNameDescriptionAndPriceButNotTheSku() throws Exception {
        Product product = givenProduct("SKU-UPD", "Old name", new BigDecimal("10.00"), 4);

        mvc.perform(put("/api/inventory/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"New name","description":"New description","price":12.25}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-UPD"))
                .andExpect(jsonPath("$.name").value("New name"))
                .andExpect(jsonPath("$.description").value("New description"))
                .andExpect(jsonPath("$.price").value(12.25))
                .andExpect(jsonPath("$.availableQuantity").value(4));
    }

    @Test
    void updatingAnUnknownProductIs404() throws Exception {
        mvc.perform(put("/api/inventory/products/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Whatever","price":1.00}
                                """))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ stock

    @Test
    void adminRestocksAProduct() throws Exception {
        Product product = givenProduct("SKU-RESTOCK", 5);

        mvc.perform(patch("/api/inventory/products/" + product.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(20));

        assertThat(products.findById(product.getId()).orElseThrow().getAvailableQuantity()).isEqualTo(20);
    }

    @Test
    void aCorrectionThatWouldGoBelowZeroIs409AndChangesNothing() throws Exception {
        Product product = givenProduct("SKU-NEGATIVE", 3);

        mvc.perform(patch("/api/inventory/products/" + product.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Stock of SKU-NEGATIVE cannot be changed by -4: available is 3"));

        assertThat(products.findById(product.getId()).orElseThrow().getAvailableQuantity()).isEqualTo(3);
    }

    @Test
    void aCorrectionCannotEatStockThatIsAlreadyReserved() throws Exception {
        Product product = givenProduct("SKU-HELD", 10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(item(product, 7)));

        // 3 units are left available, 7 are held by the order: -5 must fail even though the
        // product still has 10 units in the warehouse.
        mvc.perform(patch("/api/inventory/products/" + product.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-5}"))
                .andExpect(status().isConflict());

        Product reloaded = products.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getAvailableQuantity()).isEqualTo(3);
        assertThat(reloaded.getReservedQuantity()).isEqualTo(7);
    }

    @Test
    void aMissingDeltaIs400() throws Exception {
        Product product = givenProduct("SKU-NO-DELTA", 1);

        mvc.perform(patch("/api/inventory/products/" + product.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.delta").value("must be provided"));
    }

    // ------------------------------------------------------------------ delete

    @Test
    void adminDeletesAProduct() throws Exception {
        Product product = givenProduct("SKU-DEL", 2);

        mvc.perform(delete("/api/inventory/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        assertThat(products.findById(product.getId())).isEmpty();
    }

    @Test
    void deletingAProductWithReservedStockIs409() throws Exception {
        Product product = givenProduct("SKU-BUSY", 6);
        reservationService.reserve(UUID.randomUUID(), List.of(item(product, 2)));

        mvc.perform(delete("/api/inventory/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Product SKU-BUSY still has reserved stock and cannot be deleted"));

        assertThat(products.findById(product.getId())).isPresent();
    }

    @Test
    void aProductWhoseReservationsAreAllSettledCanBeDeleted() throws Exception {
        Product product = givenProduct("SKU-SETTLED", 6);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(item(product, 2)));
        reservationService.release(orderId);

        mvc.perform(delete("/api/inventory/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        assertThat(products.findById(product.getId())).isEmpty();
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId)).isEmpty();
    }

    // ------------------------------------------------------------------ reservations query

    @Test
    void adminListsTheReservationsOfAnOrder() throws Exception {
        Product first = givenProduct("SKU-R1", 10);
        Product second = givenProduct("SKU-R2", 10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(item(first, 2), item(second, 3)));

        mvc.perform(get("/api/inventory/reservations")
                        .param("orderId", orderId.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.sku == 'SKU-R1')].quantity").value(2))
                .andExpect(jsonPath("$[?(@.sku == 'SKU-R2')].quantity").value(3))
                .andExpect(jsonPath("$[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$[0].status").value("RESERVED"));
    }

    @Test
    void anUnknownOrderHasNoReservations() throws Exception {
        mvc.perform(get("/api/inventory/reservations")
                        .param("orderId", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theReservationsQueryNeedsAnOrderId() throws Exception {
        mvc.perform(get("/api/inventory/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.orderId").isNotEmpty());
    }

    private static OrderItem item(Product product, int quantity) {
        return new OrderItem(product.getId(), product.getSku(), quantity, product.getPrice());
    }

    private String createBody(String sku, String name, String price, int quantity) throws Exception {
        return json.writeValueAsString(json.createObjectNode()
                .put("sku", sku)
                .put("name", name)
                .put("description", "created by a test")
                .put("price", new BigDecimal(price))
                .put("availableQuantity", quantity));
    }
}
