package com.philips.ecommerce.product.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Name/value specification line. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductAttributeResponse {
    private String name;
    private String value;
}
