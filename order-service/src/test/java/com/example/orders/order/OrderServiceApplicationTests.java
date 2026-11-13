package com.example.orders.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * The context boots against a real Postgres and a real Kafka.
 *
 * <p>It shares {@link OrderIntegrationTest}'s containers instead of starting its own: a second
 * Kafka broker would cost another half gigabyte on a machine whose Docker has ~4 GB.
 */
class OrderServiceApplicationTests extends OrderIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(OrderServiceApplication.class)).isNotNull();
    }
}
