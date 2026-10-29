package com.philips.ecommerce.cart.client;

import com.philips.ecommerce.cart.client.dto.ProductDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative HTTP client. name = Eureka service id: Spring Cloud LoadBalancer
 * resolves "product-service" to one of its registered instances (client-side
 * load balancing, no hard-coded host). Timeouts are configured under
 * feign.client.config.product-service. Do not call it directly - use
 * {@link ProductServiceAdapter}, which adds retry, circuit breaker and error
 * translation.
 */
@FeignClient(name = "product-service")
public interface ProductClient {

	@GetMapping("/api/products/{id}")
	ProductDto getProduct(@PathVariable("id") Long id);
}
