package com.philips.ecommerce.cart.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI: http://localhost:8083/swagger-ui.html */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	@Bean
	public OpenAPI cartOpenApi() {
		return new OpenAPI().info(new Info().title("Cart Service API").version("1.0.0")
				.description("Customer shopping carts with price snapshots, quantity limits and stock checks."));
	}
}
