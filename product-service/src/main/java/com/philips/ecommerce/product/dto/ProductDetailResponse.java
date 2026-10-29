package com.philips.ecommerce.product.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Full product page; this is the object cached under product:v1:detail:{id}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDetailResponse {
    private Long id;
    private String sku;
    private String name;
    private String description;
    private String brand;
    private BigDecimal price;
    private String currency;
    private boolean active;
    private CategoryResponse category;
    private List<ProductImageResponse> images;
    private List<ProductAttributeResponse> attributes;
    private Instant createdAt;
    private Instant updatedAt;
}
