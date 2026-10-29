package com.philips.ecommerce.cart.client;

import com.philips.ecommerce.cart.client.dto.AvailabilityDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** See {@link ProductClient}; wrapped by {@link InventoryServiceAdapter}. */
@FeignClient(name = "inventory-service")
public interface InventoryClient {

	@GetMapping("/api/inventory/{productId}/availability")
	AvailabilityDto checkAvailability(@PathVariable("productId") Long productId,
			@RequestParam("quantity") int quantity);
}
