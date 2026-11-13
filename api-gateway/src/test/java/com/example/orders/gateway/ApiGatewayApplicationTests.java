package com.example.orders.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class ApiGatewayApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(ApiGatewayApplication.class)).isNotNull();
    }

    @Test
    void allFiveDownstreamRoutesAreConfigured() {
        List<String> routeIds = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(routeIds).contains(
                "auth-service",
                "order-service",
                "inventory-service",
                "payment-service",
                "notification-service");
    }
}
