package com.philips.ecommerce.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Reserve stock for an order (idempotent by orderNumber)")
public class ReservationRequest {

    @NotBlank
    @Size(max = 40)
    @Schema(example = "ORD-20240101-1A2B3C4D")
    private String orderNumber;

    /** @Valid cascades validation into every item (otherwise item constraints would be ignored). */
    @NotEmpty
    @Valid
    private List<ReservationItemRequest> items;
}
