package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.inventory.domain.Product;
import com.example.orders.support.correlation.CorrelationId;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The catalog is the one part of inventory-service that answers without a token. */
class ProductCatalogApiTest extends InventoryIntegrationTest {

    @Test
    void listingProductsNeedsNoToken() throws Exception {
        givenProduct("SKU-A", "Alpha keyboard", new BigDecimal("49.90"), 5);
        givenProduct("SKU-B", "Beta mouse", new BigDecimal("19.90"), 7);

        mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Alpha keyboard"))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-A"))
                .andExpect(jsonPath("$.content[0].price").value(49.90))
                .andExpect(jsonPath("$.content[0].availableQuantity").value(5))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void theCatalogNeverExposesWhatOtherOrdersAreHolding() throws Exception {
        Product product = givenProduct("SKU-HIDDEN", 9);

        mvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.getId().toString()))
                .andExpect(jsonPath("$.availableQuantity").value(9))
                .andExpect(jsonPath("$.reservedQuantity").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void pagesAreStableAndReportTheirPosition() throws Exception {
        for (int i = 0; i < 25; i++) {
            givenProduct("SKU-%02d".formatted(i), "Product %02d".formatted(i), new BigDecimal("1.00"), 1);
        }

        mvc.perform(get("/api/products").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.content[0].name").value("Product 00"))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.last").value(false));

        mvc.perform(get("/api/products").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.content[0].name").value("Product 20"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void anOverlargePageSizeIsClampedInsteadOfRejected() throws Exception {
        givenProduct("SKU-ONE", 1);

        mvc.perform(get("/api/products").param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void searchMatchesNameAndSkuCaseInsensitively() throws Exception {
        givenProduct("SKU-LAPTOP-1", "Portable computer", new BigDecimal("999.00"), 3);
        givenProduct("SKU-DESK-1", "Standing desk", new BigDecimal("399.00"), 4);

        mvc.perform(get("/api/products").param("q", "laptop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-LAPTOP-1"));

        mvc.perform(get("/api/products").param("q", "STANDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-DESK-1"));

        mvc.perform(get("/api/products").param("q", "nothing-matches-this"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void anUnknownProductIdIs404InTheSharedErrorShape() throws Exception {
        UUID unknown = UUID.randomUUID();

        mvc.perform(get("/api/products/" + unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Product " + unknown + " does not exist"))
                .andExpect(jsonPath("$.path").value("/api/products/" + unknown))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void anUnparsableProductIdIs400() throws Exception {
        mvc.perform(get("/api/products/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.id").isNotEmpty());
    }

    // ------------------------------------------------------------------ correlation id

    @Test
    void aSaneCorrelationIdIsEchoedBack() throws Exception {
        mvc.perform(get("/api/products").header(CorrelationId.HEADER, "gateway-correlation-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationId.HEADER, "gateway-correlation-1"));
    }

    @Test
    void aMissingCorrelationIdIsGenerated() throws Exception {
        String generated = mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(CorrelationId.HEADER);

        assertThat(generated).isNotNull();
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    @Test
    void anAbusiveCorrelationIdIsReplaced() throws Exception {
        String echoed = mvc.perform(get("/api/products")
                        .header(CorrelationId.HEADER, "not sane; drop table products"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(CorrelationId.HEADER);

        assertThat(echoed).isNotNull().doesNotContain("drop table");
        assertThat(UUID.fromString(echoed)).isNotNull();
    }

    @Test
    void evenAnErrorResponseCarriesTheCorrelationId() throws Exception {
        mvc.perform(get("/api/products/" + UUID.randomUUID()).header(CorrelationId.HEADER, "error-path-id"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(CorrelationId.HEADER, "error-path-id"));
    }
}
