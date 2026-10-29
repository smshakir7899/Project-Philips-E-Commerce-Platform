package com.philips.ecommerce.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationItemRequest {

    @NotNull
    @Schema(example = "1")
    private Long productId;

    @NotNull
    @Min(1)
    @Schema(example = "2")
    private Integer quantity;
}
