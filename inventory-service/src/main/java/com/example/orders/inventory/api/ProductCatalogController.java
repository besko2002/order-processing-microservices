package com.example.orders.inventory.api;

import com.example.orders.inventory.service.ProductService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public product catalog: browsing needs no account, which is why these two endpoints are the
 * only ones the gateway and this service leave open.
 */
@RestController
@RequestMapping("/api/products")
class ProductCatalogController {

    private final ProductService products;

    ProductCatalogController(ProductService products) {
        this.products = products;
    }

    /**
     * @param page zero-based page index
     * @param size page size, clamped to at most {@value ProductService#MAX_PAGE_SIZE}
     * @param q    optional case-insensitive search over name and SKU
     * @return one page of products in the shared envelope
     */
    @GetMapping
    PageResponse<ProductResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + ProductService.DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) String q) {
        return PageResponse.of(products.list(page, size, q), ProductResponse::of);
    }

    /**
     * @param id product id
     * @return the product, including its price and available quantity
     */
    @GetMapping("/{id}")
    ProductResponse get(@PathVariable UUID id) {
        return ProductResponse.of(products.get(id));
    }
}
