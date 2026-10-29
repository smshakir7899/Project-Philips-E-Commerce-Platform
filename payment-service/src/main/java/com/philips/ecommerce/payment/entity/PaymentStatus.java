package com.philips.ecommerce.payment.entity;

/**
 * PROCESSING -> SUCCEEDED | FAILED; SUCCEEDED -> REFUNDED. FAILED and REFUNDED are terminal.
 * PROCESSING is persisted BEFORE the gateway call so that a retried request (same idempotency key) can see that a
 * charge is in flight instead of charging twice.
 */
public enum PaymentStatus {
    PROCESSING,
    SUCCEEDED,
    FAILED,
    REFUNDED
}
