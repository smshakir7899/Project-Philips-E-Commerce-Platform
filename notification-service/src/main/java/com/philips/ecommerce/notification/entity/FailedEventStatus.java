package com.philips.ecommerce.notification.entity;

/** NEW = waiting for an operator; REPLAYED = republished to its original topic. */
public enum FailedEventStatus {
    NEW,
    REPLAYED
}
