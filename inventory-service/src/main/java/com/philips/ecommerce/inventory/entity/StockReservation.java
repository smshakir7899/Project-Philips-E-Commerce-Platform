package com.philips.ecommerce.inventory.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.CascadeType;
import javax.persistence.Column;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stock held for one order. {@code orderNumber} is unique: it is the idempotency key that makes
 * POST /api/inventory/reservations safe to retry (order-service retries it with Resilience4j).
 */
@Entity
@Table(name = "stock_reservation",
        uniqueConstraints = @UniqueConstraint(name = "uk_reservation_order_number", columnNames = "order_number"),
        indexes = @Index(name = "idx_reservation_status_expires", columnList = "status, expires_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, length = 40)
    private String orderNumber;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Owned by the reservation: saved, and deleted, together with it. */
    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("productId ASC")
    private List<StockReservationItem> items = new ArrayList<>();

    public StockReservation(String orderNumber, Instant createdAt, Instant expiresAt) {
        this.orderNumber = orderNumber;
        this.status = ReservationStatus.RESERVED;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.updatedAt = createdAt;
    }

    /** Keeps both sides of the bidirectional association in sync. */
    public void addItem(Long productId, int quantity) {
        items.add(new StockReservationItem(this, productId, quantity));
    }

    public boolean isExpiredAt(Instant now) {
        return status == ReservationStatus.RESERVED && expiresAt.isBefore(now);
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
