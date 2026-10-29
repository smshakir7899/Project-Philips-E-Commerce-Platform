package com.philips.ecommerce.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Same JSON shape as common-lib's {@code ApiErrorResponse} (the gateway cannot depend on common-lib because that
 * library is servlet-based). Keeping the shape identical means clients parse ONE error format, whether the error
 * was produced by a service or by the gateway itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayErrorResponse {
    private Instant timestamp;
    private int status;
    private String error;
    private String code;
    private String message;
    private String path;
    private String correlationId;
}
