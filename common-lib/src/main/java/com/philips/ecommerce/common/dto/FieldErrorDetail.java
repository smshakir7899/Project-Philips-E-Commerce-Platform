package com.philips.ecommerce.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One bean-validation failure, e.g. {"field":"quantity","message":"must be greater than or equal to 1"}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldErrorDetail {
    private String field;
    private String message;
    private Object rejectedValue;
}
