package com.philips.ecommerce.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** A line item carried inside order events. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemPayload {
    private Long productId;
    private String sku;
    private String productName;
    private Integer quantity;
    private BigDecimal unitPrice;
}
