package com.philips.ecommerce.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemResponse {
	@Schema(example = "1")
	private Long productId;
	@Schema(example = "PH-SONIC-9900")
	private String sku;
	private String productName;
	@Schema(description = "Price snapshot taken when the line was added/updated", example = "329.99")
	private BigDecimal unitPrice;
	@Schema(example = "2")
	private int quantity;
	@Schema(example = "659.98")
	private BigDecimal lineTotal;
}
