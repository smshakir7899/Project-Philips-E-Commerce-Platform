package com.philips.ecommerce.product.dto;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Payload of POST /api/products and PUT /api/products/{id}. Attributes replace the existing list on update. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductRequest {
    @NotBlank
    @Size(max = 64)
    private String sku;

    @NotBlank
    @Size(max = 200)
    private String name;

    @Size(max = 4000)
    private String description;

    @NotBlank
    @Size(max = 100)
    private String brand;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal price;

    /** Optional, defaults to EUR. */
    @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code such as EUR")
    private String currency;

    @NotNull
    private Long categoryId;

    @Valid
    private List<ProductAttributeRequest> attributes;
}
