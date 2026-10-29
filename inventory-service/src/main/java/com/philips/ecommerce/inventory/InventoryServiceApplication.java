package com.philips.ecommerce.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * inventory-service: owns stock levels and the reservations made for orders during checkout.
 *
 * <p>Deliberately no @EnableScheduling / @EnableKafka here: annotations on the main class are also applied to every
 * test slice (@WebMvcTest, @DataJpaTest). They live in dedicated @Configuration classes instead.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
