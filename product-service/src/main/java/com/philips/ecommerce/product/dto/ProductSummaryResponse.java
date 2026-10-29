package com.philips.ecommerce.product.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Listing / batch representation. Also what order-service and cart-service read (authoritative price, active flag). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSummaryResponse {
    private Long id;
    private String sku;
    private String name;
    private String brand;
    private BigDecimal price;
    private String currency;
    private Long categoryId;
    private String categoryName;
    private boolean active;
    /** URL of the image with sortOrder 0; absent (null, omitted in JSON) when the product has no image. */
    private String thumbnailUrl;
}
