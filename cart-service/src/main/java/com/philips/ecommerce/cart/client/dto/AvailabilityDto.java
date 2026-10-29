package com.philips.ecommerce.cart.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mirrors inventory-service's AvailabilityResponse. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AvailabilityDto {
    private Long productId;
    private int requestedQuantity;
    private int availableQuantity;
    private boolean available;
}
