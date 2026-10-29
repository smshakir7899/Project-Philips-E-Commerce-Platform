package com.philips.ecommerce.product.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Payload of POST /api/categories. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryRequest {
    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "[a-z0-9-]+", message = "must contain only lower-case letters, digits and dashes")
    private String slug;

    @Size(max = 500)
    private String description;
}
