package com.philips.ecommerce.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for all expected, domain-level failures. GlobalExceptionHandler converts it into an
 * {@link com.philips.ecommerce.common.dto.ApiErrorResponse} with the given HTTP status and error code.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details = new HashMap<>();

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public BusinessException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public BusinessException withDetail(String key, Object value) {
        details.put(key, value);
        return this;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return Collections.unmodifiableMap(details);
    }
}
