package com.philips.ecommerce.order.outbox;

/** Lifecycle of an outbox row: written PENDING with the business change, then SENT (or FAILED after max attempts). */
public enum OutboxStatus {
    PENDING,
    SENT,
    FAILED
}
