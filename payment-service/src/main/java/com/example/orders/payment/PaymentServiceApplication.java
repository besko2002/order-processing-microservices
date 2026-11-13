package com.example.orders.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Simulated payments and refunds.
 *
 * <p>The two scan annotations bring in the shared outbox and {@code processed_events} mappings from
 * {@code service-support}; the tables themselves are created by this service's own Flyway migration
 * in its own database.
 */
@SpringBootApplication
@EntityScan(basePackages = {"com.example.orders.payment", "com.example.orders.support"})
@EnableJpaRepositories(basePackages = {"com.example.orders.payment", "com.example.orders.support"})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
