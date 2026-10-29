package com.philips.ecommerce.order.client;

import com.philips.ecommerce.order.client.dto.ReservationDto;
import com.philips.ecommerce.order.client.dto.ReservationRequestDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "inventory-service")
public interface InventoryClient {

    @PostMapping("/api/inventory/reservations")
    ReservationDto reserve(@RequestBody ReservationRequestDto request);
}
