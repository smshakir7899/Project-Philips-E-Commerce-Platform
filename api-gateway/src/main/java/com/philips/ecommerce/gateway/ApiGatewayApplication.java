package com.philips.ecommerce.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single public entry point (port 8080). Spring Cloud Gateway runs on Reactor Netty: a small number of event-loop
 * threads handle all connections without blocking, which is why gateway code must never block (no JDBC, no
 * Thread.sleep, no blocking HTTP clients).
 *
 * <p>Request flow: CORS -> {@link com.philips.ecommerce.gateway.filter.CorrelationIdGlobalFilter} -> route predicates
 * (config-repo/api-gateway.yml) -> route filters (CircuitBreaker, Retry) -> LoadBalancer resolves {@code lb://name}
 * through the Eureka registry -> Netty HTTP client -> downstream service.</p>
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
