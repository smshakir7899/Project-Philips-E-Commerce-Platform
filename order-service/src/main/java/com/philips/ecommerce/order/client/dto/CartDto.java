package com.philips.ecommerce.order.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Local copy of cart-service's CartResponse (GET /api/carts/{customerId}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CartDto {
    private String customerId;
    private List<CartItemDto> items;
    private Integer totalQuantity;
    private BigDecimal subtotal;
    private String currency;
    private Instant updatedAt;
}
