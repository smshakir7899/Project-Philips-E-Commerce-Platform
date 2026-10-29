package com.philips.ecommerce.payment.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PostLoad;
import javax.persistence.PostPersist;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.time.Instant;

/**
 * Tombstone: "this order was cancelled while NO payment for it existed here".
 * <p>
 * Why it is needed: order-service may give up on a payment call (read timeout) and publish order-cancelled while
 * its HTTP request is still in flight (slow network, retry, queued request). If order-cancelled is consumed first,
 * there is nothing to refund yet - and without a record of the cancellation the late request would charge the
 * customer for an order that no longer exists. With the tombstone, {@code PaymentService} refuses such a request
 * (FAILED ORDER_ALREADY_CANCELLED, gateway never called), and the completion step re-checks it for the case where
 * the request and the event raced.
 * <p>
 * Same {@link Persistable} trick as {@link ProcessedEvent}: the id (orderNumber) is assigned by us, so a transient
 * flag makes {@code save()} issue a plain INSERT instead of merge's SELECT + INSERT.
 */
@Entity
@Table(name = "cancelled_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CancelledOrder implements Persistable<String> {

    @Id
    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    /** CancellationReason of the order-cancelled event, e.g. PAYMENT_ERROR. */
    @Column(name = "reason", nullable = false, length = 32)
    private String reason;

    @Column(name = "cancelled_at", nullable = false)
    private Instant cancelledAt;

    @Transient
    private boolean newEntity = true;

    public CancelledOrder(String orderNumber, String reason, Instant cancelledAt) {
        this.orderNumber = orderNumber;
        this.reason = reason;
        this.cancelledAt = cancelledAt;
    }

    @Override
    public String getId() {
        return orderNumber;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }
}
