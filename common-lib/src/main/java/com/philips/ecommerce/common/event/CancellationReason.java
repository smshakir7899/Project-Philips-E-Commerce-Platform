package com.philips.ecommerce.common.event;

/** Why an order was cancelled; drives compensation in inventory-service and payment-service. */
public enum CancellationReason {
    /** Inventory reservation was rejected (nothing was reserved). */
    INSUFFICIENT_STOCK,
    /** Inventory service failed or timed out while reserving. */
    INVENTORY_ERROR,
    /** Simulated gateway declined the payment. */
    PAYMENT_DECLINED,
    /** Payment service failed or timed out; payment outcome may be unknown -> payment-service must void/refund. */
    PAYMENT_ERROR,
    /** Customer cancelled a confirmed order before shipping. */
    CUSTOMER_REQUESTED
}
