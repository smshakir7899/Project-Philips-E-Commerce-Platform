package com.philips.ecommerce.order.client;

import com.philips.ecommerce.order.client.dto.CartDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative HTTP client. "cart-service" is the Eureka service id: Spring Cloud LoadBalancer resolves it to a live
 * instance on every call (client-side load balancing). Timeouts: feign.client.config.cart-service.* in config-repo.
 * Error responses are turned into RemoteServiceException by common-lib's RemoteServiceErrorDecoder.
 */
@FeignClient(name = "cart-service")
public interface CartClient {

    @GetMapping("/api/carts/{customerId}")
    CartDto getCart(@PathVariable("customerId") String customerId);
}
