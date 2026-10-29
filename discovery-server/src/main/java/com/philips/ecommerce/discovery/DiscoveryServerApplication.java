package com.philips.ecommerce.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka service registry. Every business service and the api-gateway register here under their
 * {@code spring.application.name}; the gateway ({@code lb://product-service}) and OpenFeign clients
 * ({@code @FeignClient(name = "product-service")}) resolve instances through Spring Cloud LoadBalancer,
 * which reads the locally cached registry (refreshed every few seconds).
 *
 * <p>Self-contained on purpose (no config-server import): the registry must be able to start with zero
 * dependencies, otherwise one outage would cascade into "nothing can find anything".</p>
 *
 * <p>Dashboard: http://localhost:8761/ - registry REST API: http://localhost:8761/eureka/apps</p>
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}
