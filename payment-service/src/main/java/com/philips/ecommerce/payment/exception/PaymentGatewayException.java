package com.philips.ecommerce.payment.exception;

/**
 * Technical failure of the (simulated) payment gateway - the charge outcome is "error", not "declined".
 * Internal exception: PaymentService converts it into 503 PAYMENT_GATEWAY_ERROR for the API caller.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
