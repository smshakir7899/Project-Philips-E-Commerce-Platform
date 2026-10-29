package com.philips.ecommerce.order.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Local copy of cart-service's CartItemResponse. Each service owns its client DTOs (instead of sharing a jar with
 * the provider) so that the provider can add fields without breaking us; unknown fields are ignored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CartItemDto {
    private Long productId;
    private String sku;
    private String productName;
    /** Price snapshot taken when the item was added to the cart - NOT authoritative for checkout. */
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal lineTotal;
}
