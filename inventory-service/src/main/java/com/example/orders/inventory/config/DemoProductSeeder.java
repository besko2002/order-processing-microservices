package com.example.orders.inventory.config;

import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Puts a handful of products in the catalog so a fresh local environment has something to show.
 *
 * <p>Only active with {@code INVENTORY_SEED_DEMO=true}, and idempotent: products are matched by
 * SKU, so a restart neither duplicates them nor resets stock somebody has been playing with.
 */
@Component
@ConditionalOnProperty(prefix = "inventory", name = "seed-demo", havingValue = "true")
public class DemoProductSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoProductSeeder.class);

    private final ProductRepository products;

    /**
     * @param products catalog repository
     */
    public DemoProductSeeder(ProductRepository products) {
        this.products = products;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /**
     * Inserts the demo products that are missing.
     *
     * @return how many products this call inserted
     */
    public int seed() {
        List<Product> demo = List.of(
                Product.create("DEMO-LAPTOP-14", "Demo Laptop 14\"",
                        "Lightweight 14-inch development laptop", new BigDecimal("1299.00"), 25),
                Product.create("DEMO-PHONE-X", "Demo Phone X",
                        "Mid-range phone with a very good camera", new BigDecimal("649.50"), 40),
                Product.create("DEMO-HEADSET-PRO", "Demo Headset Pro",
                        "Noise cancelling over-ear headset", new BigDecimal("199.99"), 100),
                Product.create("DEMO-KEYBOARD-87", "Demo Keyboard 87",
                        "Tenkeyless mechanical keyboard", new BigDecimal("89.90"), 60),
                Product.create("DEMO-MOUSE-ERGO", "Demo Mouse Ergo",
                        "Ergonomic wireless mouse", new BigDecimal("49.00"), 75));

        int inserted = 0;
        for (Product product : demo) {
            if (products.existsBySku(product.getSku())) {
                continue;
            }
            try {
                products.saveAndFlush(product);
                inserted++;
            }
            catch (DataIntegrityViolationException e) {
                // Another instance seeded the same SKU between the check and the insert.
                log.debug("Demo product {} was seeded concurrently", product.getSku(), e);
            }
        }
        log.info("Demo seeding done: {} product(s) inserted, catalog now holds {}", inserted, products.count());
        return inserted;
    }
}
