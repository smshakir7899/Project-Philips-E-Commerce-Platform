package com.philips.ecommerce.cart.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * The subset of product-service's ProductDetailResponse this service needs.
 * Declaring our own DTO (instead of sharing product-service classes) keeps the
 * services independently deployable: unknown fields are ignored, so
 * product-service may add fields without breaking us (tolerant reader).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductDto {
	private Long id;
	private String sku;
	private String name;
	private BigDecimal price;
	private String currency;
	private boolean active;
}
