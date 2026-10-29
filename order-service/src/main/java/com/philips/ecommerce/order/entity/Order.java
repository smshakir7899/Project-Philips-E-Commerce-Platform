package com.philips.ecommerce.order.entity;

import com.philips.ecommerce.common.exception.ConflictException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Order aggregate root. All state changes go through {@link #transitionTo(OrderStatus, String)} so the state machine
 * in {@link OrderStatus} cannot be bypassed and every change is recorded in the status history.
 * <p>
 * Table name "orders" because ORDER is a reserved SQL word.
 * Lombok: only @Getter (no @Data/@Setter) - entities need controlled mutation, and Lombok's equals/hashCode over
 * all fields would touch lazy collections and break in hash-based collections when the id is assigned on insert.
 */
@Entity
@Table(name = "orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_orders_order_number", columnNames = "order_number"),
                @UniqueConstraint(name = "uk_orders_idempotency_key", columnNames = "idempotency_key")
        },
        indexes = {
                // Serves "order history of a customer, newest first" (WHERE customer_id = ? ORDER BY created_at DESC).
                @Index(name = "idx_orders_customer_created", columnList = "customer_id, created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    /** Client-supplied Idempotency-Key header; the UNIQUE constraint is what makes concurrent retries safe. */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "payment_reference", length = 32)
    private String paymentReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Embedded
    private ShippingAddress shippingAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic locking: concurrent updates of the same order (e.g. customer cancel racing with an admin "ship")
     * cannot silently overwrite each other - the loser gets an OptimisticLockException (409 CONCURRENT_MODIFICATION).
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("changedAt ASC, id ASC")
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    /** Factory for a new order in status CREATED; the total is computed from the (authoritative) item prices. */
    public static Order create(String orderNumber, String customerId, String idempotencyKey, String currency,
                               ShippingAddress shippingAddress, List<OrderItem> items) {
        Order order = new Order();
        order.orderNumber = orderNumber;
        order.customerId = customerId;
        order.idempotencyKey = idempotencyKey;
        order.currency = currency;
        order.shippingAddress = shippingAddress;
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : items) {
            item.setOrder(order);
            order.items.add(item);
            total = total.add(item.getLineTotal());
        }
        order.totalAmount = total.setScale(2, RoundingMode.HALF_UP);
        order.status = OrderStatus.CREATED;
        order.statusHistory.add(new OrderStatusHistory(order, null, OrderStatus.CREATED, "Order placed", Instant.now()));
        return order;
    }

    /**
     * Moves the order to {@code target} if the state machine allows it and appends a history entry.
     *
     * @throws ConflictException INVALID_STATUS_TRANSITION (HTTP 409) otherwise
     */
    public void transitionTo(OrderStatus target, String reason) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException("INVALID_STATUS_TRANSITION",
                    "Order " + orderNumber + " cannot change from " + status + " to " + target)
                    .withDetail("orderNumber", orderNumber)
                    .withDetail("currentStatus", status.name())
                    .withDetail("requestedStatus", String.valueOf(target));
        }
        statusHistory.add(new OrderStatusHistory(this, status, target, reason, Instant.now()));
        this.status = target;
    }

    public void assignPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public void recordFailure(String failureReason) {
        this.failureReason = failureReason != null && failureReason.length() > 500
                ? failureReason.substring(0, 500) : failureReason;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<OrderStatusHistory> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
