package com.philips.ecommerce.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * cart-service: customer shopping carts. Validates products and stock
 * synchronously (Feign) and clears the cart asynchronously when an order is
 * confirmed (Kafka).
 *
 * <p>
 * @EnableFeignClients lives in
 * {@link com.philips.ecommerce.cart.config.FeignConfig}, not here: annotations
 * on the main class also apply to test slices such as @WebMvcTest, which do not
 * contain the Feign infrastructure.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CartServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CartServiceApplication.class, args);
	}
}
