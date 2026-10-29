package com.philips.ecommerce.notification.entity;

/** What the customer is told about; one type per consumed event (and outcome). */
public enum NotificationType {
    ORDER_PLACED,
    ORDER_CONFIRMED,
    ORDER_CANCELLED,
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED,
    PAYMENT_REFUNDED
}
