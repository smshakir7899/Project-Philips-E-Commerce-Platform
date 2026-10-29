package com.philips.ecommerce.common.exception;

import org.springframework.http.HttpStatus;

/** 409 - the request is valid but conflicts with current state (e.g. INSUFFICIENT_STOCK, INVALID_STATUS_TRANSITION). */
public class ConflictException extends BusinessException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
