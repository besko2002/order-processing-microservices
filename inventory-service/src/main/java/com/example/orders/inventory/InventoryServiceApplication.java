package com.example.orders.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Products, stock, and the saga's stock steps.
 *
 * <p>The two scan annotations bring in the shared outbox and {@code processed_events} mappings from
 * {@code service-support}; the tables themselves are created by this service's own Flyway migrations
 * in its own database.
 */
@SpringBootApplication
@EntityScan(basePackages = {"com.example.orders.inventory", "com.example.orders.support"})
@EnableJpaRepositories(basePackages = {"com.example.orders.inventory", "com.example.orders.support"})
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
