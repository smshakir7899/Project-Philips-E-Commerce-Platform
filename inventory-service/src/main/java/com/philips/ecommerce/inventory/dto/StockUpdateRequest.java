package com.philips.ecommerce.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Admin upsert of a product's stock level")
public class StockUpdateRequest {

    @NotBlank
    @Size(max = 64)
    @Schema(example = "PH-SHAVER-S9000")
    private String sku;

    @NotNull
    @Min(0)
    @Schema(description = "New sellable quantity (reserved stock is not affected)", example = "100")
    private Integer availableQuantity;

    @NotNull
    @Min(0)
    @Schema(example = "5")
    private Integer lowStockThreshold;
}
