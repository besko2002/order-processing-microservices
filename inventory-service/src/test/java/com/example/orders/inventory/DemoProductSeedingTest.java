package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.inventory.config.DemoProductSeeder;
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
}
