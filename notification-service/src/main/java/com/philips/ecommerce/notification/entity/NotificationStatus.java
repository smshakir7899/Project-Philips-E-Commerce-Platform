package com.philips.ecommerce.notification.entity;

/** SENT = delivered (simulated); FAILED = last delivery attempt threw (the record is being retried or dead-lettered). */
public enum NotificationStatus {
    SENT,
    FAILED
}
