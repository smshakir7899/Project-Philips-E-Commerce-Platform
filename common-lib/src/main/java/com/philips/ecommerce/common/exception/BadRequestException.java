package com.philips.ecommerce.common.exception;

import org.springframework.http.HttpStatus;

/** 400 - semantically invalid input that bean validation cannot express (e.g. EMPTY_CART, INVALID_PRODUCT). */
public class BadRequestException extends BusinessException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
