package com.philips.ecommerce.common.exception;

import org.springframework.http.HttpStatus;

/** 404 - e.g. code PRODUCT_NOT_FOUND, ORDER_NOT_FOUND. */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
