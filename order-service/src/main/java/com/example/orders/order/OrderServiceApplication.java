package com.example.orders.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The saga orchestrator.
 *
 * <p>The two scan annotations are what let {@code service-support} carry the transactional outbox
 * and the {@code processed_events} de-duplication log: the entities and repositories live in that
 * module, the tables live in this service's own database (created by this service's own Flyway
 * migrations), and naming both package roots here keeps that arrangement visible instead of hiding
 * it inside a library.
 */
@SpringBootApplication
@EntityScan(basePackages = {"com.example.orders.order", "com.example.orders.support"})
@EnableJpaRepositories(basePackages = {"com.example.orders.order", "com.example.orders.support"})
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
