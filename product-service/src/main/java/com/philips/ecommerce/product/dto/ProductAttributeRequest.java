package com.philips.ecommerce.product.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One attribute line of ProductRequest. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductAttributeRequest {
    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 500)
    private String value;
}
