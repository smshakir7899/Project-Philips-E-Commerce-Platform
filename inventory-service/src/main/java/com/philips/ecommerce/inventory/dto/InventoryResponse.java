package com.philips.ecommerce.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Stock level of a product")
public class InventoryResponse {
    @Schema(example = "1")
    private Long productId;
    @Schema(example = "PH-SHAVER-S9000")
    private String sku;
    @Schema(description = "Quantity that can still be sold", example = "42")
    private int availableQuantity;
    @Schema(description = "Quantity held for orders in checkout", example = "3")
    private int reservedQuantity;
    @Schema(description = "At or below this available quantity the product is considered low on stock", example = "5")
    private int lowStockThreshold;
    @Schema(description = "true when availableQuantity > 0")
    private boolean inStock;
    private Instant updatedAt;
}
