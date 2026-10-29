package com.philips.ecommerce.payment.outbox;

/** Lifecycle of an outbox row: written PENDING with the business change, then SENT (or FAILED after max attempts). */
public enum OutboxStatus {
    PENDING,
    SENT,
    FAILED
}
