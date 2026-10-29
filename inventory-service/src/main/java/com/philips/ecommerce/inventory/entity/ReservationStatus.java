package com.philips.ecommerce.inventory.entity;

/**
 * Life cycle of a stock reservation.
 * <pre>
 *   RESERVED --commit--> COMMITTED --release--> RETURNED   (order cancelled after confirmation: restock)
 *   RESERVED --release-> RELEASED                          (order cancelled / payment failed)
 *   RESERVED --ttl-----> EXPIRED                           (saga never finished: safety net)
 * </pre>
 * RELEASED, EXPIRED and RETURNED are terminal for release; COMMITTED is terminal for commit.
 */
public enum ReservationStatus {
    RESERVED,
    COMMITTED,
    RELEASED,
    EXPIRED,
    RETURNED
}
