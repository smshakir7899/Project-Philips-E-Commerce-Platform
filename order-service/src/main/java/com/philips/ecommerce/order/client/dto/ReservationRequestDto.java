package com.philips.ecommerce.order.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Body of POST /api/inventory/reservations. The orderNumber doubles as the reservation's idempotency key. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRequestDto {
    private String orderNumber;
    private List<ReservationItemDto> items;
}
