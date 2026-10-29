package com.philips.ecommerce.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddCartItemRequest {

    @NotNull
    @Schema(example = "1")
    private Long productId;

    /** Quantity to ADD to the line (the resulting line total is limited to 10 by the service). */
    @NotNull
    @Min(1)
    @Max(10)
    @Schema(example = "2")
    private Integer quantity;
}
