package com.philips.ecommerce.order.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Subset of product-service's ProductSummaryResponse returned by GET /api/products/batch?ids=. */
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
