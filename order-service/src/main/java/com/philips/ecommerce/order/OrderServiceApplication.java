package com.philips.ecommerce.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * order-service: owns orders and orchestrates the checkout saga (cart -> product prices -> inventory reservation ->
 * payment) with Feign + Resilience4j, and publishes order events through a transactional outbox.
 * Feature switches (@EnableFeignClients, @EnableScheduling) live in config classes, see FeignConfig.
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
