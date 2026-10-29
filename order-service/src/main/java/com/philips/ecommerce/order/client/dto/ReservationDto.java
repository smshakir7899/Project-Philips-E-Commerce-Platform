package com.philips.ecommerce.order.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** inventory-service's ReservationResponse. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReservationDto {
    private String orderNumber;
    private String status;
    private List<ReservationItemDto> items;
    private Instant createdAt;
    private Instant expiresAt;
}
