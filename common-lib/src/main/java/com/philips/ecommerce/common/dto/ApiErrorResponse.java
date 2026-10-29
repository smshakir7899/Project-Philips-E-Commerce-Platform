package com.philips.ecommerce.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Uniform error body returned by EVERY service (produced by GlobalExceptionHandler).
 * Feign clients in other services parse this body to turn remote errors into local exceptions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiErrorResponse {
    private Instant timestamp;
    private int status;
    private String error;
    /** Machine-readable error code, e.g. INSUFFICIENT_STOCK, PRODUCT_NOT_FOUND, VALIDATION_FAILED. */
    private String code;
    private String message;
    private String path;
    private String correlationId;
    private List<FieldErrorDetail> fieldErrors;
    private Map<String, Object> details;
}
