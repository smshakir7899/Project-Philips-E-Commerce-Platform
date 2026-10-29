package com.philips.ecommerce.common.exception;

import org.springframework.http.HttpStatus;

/** 503 - a downstream dependency timed out, refused the connection, or its circuit breaker is open. */
public class ServiceUnavailableException extends BusinessException {

    public ServiceUnavailableException(String code, String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }

    public ServiceUnavailableException(String code, String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, code, message, cause);
    }
}
