package com.philips.ecommerce.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Result of POST /api/payments: the payment plus whether THIS request created it
 * (true -> 201 Created, false -> 200 OK idempotent replay of an existing payment).
 */
@Getter
@AllArgsConstructor
public class PaymentProcessingResult {
    private final PaymentResponse payment;
    private final boolean created;
}
