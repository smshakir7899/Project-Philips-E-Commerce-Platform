package com.philips.ecommerce.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Advisory answer used by cart-service; the authoritative check happens when the order reserves stock. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Whether the requested quantity can currently be supplied")
public class AvailabilityResponse {
    private Long productId;
    private int requestedQuantity;
    private int availableQuantity;
    private boolean available;
}
