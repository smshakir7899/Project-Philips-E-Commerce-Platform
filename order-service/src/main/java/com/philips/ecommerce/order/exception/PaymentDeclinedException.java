package com.philips.ecommerce.order.exception;

import com.philips.ecommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 402 PAYMENT_DECLINED: the (simulated) gateway refused the card. common-lib has no 402 subclass, so this service
 * defines one; GlobalExceptionHandler renders any BusinessException with its own status and code.
 */
public class PaymentDeclinedException extends BusinessException {

    public PaymentDeclinedException(String message) {
        super(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", message);
    }
}
