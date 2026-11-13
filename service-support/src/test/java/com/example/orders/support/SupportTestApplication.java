package com.example.orders.support;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * A minimal service built on this module, used by the reliability tests.
 *
 * <p>It exists because the outbox, the de-duplication log and the dead-letter handling are
 * auto-configurations: the only honest way to test them is to boot an application that opts in
 * exactly as order-service, inventory-service and payment-service do — including the
 * {@code @EntityScan}/{@code @EnableJpaRepositories} pair that brings this module's mappings in.
 */
@SpringBootApplication
@EntityScan(basePackages = "com.example.orders.support")
@EnableJpaRepositories(basePackages = "com.example.orders.support")
public class SupportTestApplication {
}
