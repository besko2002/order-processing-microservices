package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.inventory.config.DemoProductSeeder;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The demo seeder is off in tests (that is the default), so it is driven directly here — what
 * matters is that it is idempotent, because it runs on every start of a local environment.
 */
class DemoProductSeedingTest extends InventoryIntegrationTest {

    @Test
    void seedingIsIdempotentAndNeverResetsStock() {
        DemoProductSeeder seeder = new DemoProductSeeder(products);

        int firstRun = seeder.seed();
        assertThat(firstRun).isPositive();
        assertThat(products.count()).isEqualTo(firstRun);

        // Somebody sells a few units of a demo product...
        var laptop = products.findBySku("DEMO-LAPTOP-14").orElseThrow();
        int before = laptop.getAvailableQuantity();
        laptop.adjustAvailable(-5);
        products.saveAndFlush(laptop);

        // ...and a restart must neither duplicate the catalog nor put the units back.
        assertThat(seeder.seed()).isZero();
        assertThat(products.count()).isEqualTo(firstRun);
        assertThat(products.findBySku("DEMO-LAPTOP-14").orElseThrow().getAvailableQuantity())
                .isEqualTo(before - 5);
    }

    @Test
    void theSeederIsNotActiveByDefault() {
        // No demo products in the context under test: seed-demo defaults to false.
        assertThat(products.findBySku("DEMO-PHONE-X")).isEmpty();
    }

    @Test
    void theSeededCatalogCoversEveryDemoBranch() {
        // The demo has to show all five branches, and three of them depend on what is seeded:
        // a cheap product that completes, a scarce one that runs out of stock, and an expensive
        // one that the simulated provider declines.
        DemoProductSeeder seeder = new DemoProductSeeder(products);
        assertThat(seeder.seed()).isPositive();

        var headset = products.findBySku("DEMO-HEADSET-PRO").orElseThrow();
        assertThat(headset.getPrice()).isLessThan(new BigDecimal("1000.00"));
        assertThat(headset.getAvailableQuantity()).isGreaterThan(1);

        var scarce = products.findBySku("DEMO-LIMITED-SPEAKER").orElseThrow();
        assertThat(scarce.getAvailableQuantity()).isEqualTo(2);

        var expensive = products.findBySku("DEMO-PREMIUM-MONITOR").orElseThrow();
        assertThat(expensive.getPrice()).isGreaterThan(new BigDecimal("1000.00"));
    }

    @Test
    void everySeededProductIsFictional() {
        new DemoProductSeeder(products).seed();

        // No real brand, catalogue or price list: the SKUs are demo-prefixed and the names are
        // generic. A real product must never sneak into the demo catalog.
        assertThat(products.findAll())
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.getSku()).startsWith("DEMO-"))
                .allSatisfy(p -> assertThat(p.getName()).startsWith("Demo "));
    }
}
