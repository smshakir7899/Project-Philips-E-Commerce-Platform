package com.philips.ecommerce.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A customer's cart; an empty cart is returned when none exists")
public class CartResponse {
	@Schema(example = "CUST-1001")
	private String customerId;
	private List<CartItemResponse> items;
	private int totalQuantity;
	@Schema(example = "659.98")
	private BigDecimal subtotal;
	@Schema(example = "EUR")
	private String currency;
	private Instant updatedAt;
}
