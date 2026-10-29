package com.philips.ecommerce.inventory.dto;

import com.philips.ecommerce.inventory.entity.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Stock reservation of an order")
public class ReservationResponse {
    @Schema(example = "ORD-20240101-1A2B3C4D")
    private String orderNumber;
    private ReservationStatus status;
    private List<ReservationItemResponse> items;
    private Instant createdAt;
    private Instant expiresAt;
}
