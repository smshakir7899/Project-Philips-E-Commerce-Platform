package com.philips.ecommerce.inventory.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.persistence.Version;
import java.time.Instant;

/**
 * Stock level of one product.
 *
 * <ul>
 *   <li>{@code availableQuantity} - can still be sold / reserved.</li>
 *   <li>{@code reservedQuantity} - held for orders whose payment is in progress (not yet sold, not sellable).</li>
 * </ul>
 * Physical stock on the shelf = available + reserved. A commit removes the reserved part (sold), a release moves it
 * back to available.
 *
 * <p>Concurrency: reservations lock the row with SELECT ... FOR UPDATE (pessimistic), admin stock updates rely on
 * {@link #version} (optimistic). See ReservationService and InventoryService for the reasoning.
 * State changes go through intention-revealing methods instead of public setters so invariants
 * (no negative quantities) are enforced in one place.
 */
@Entity
@Table(name = "inventory", uniqueConstraints = {
        @UniqueConstraint(name = "uk_inventory_product", columnNames = "product_id"),
        @UniqueConstraint(name = "uk_inventory_sku", columnNames = "sku")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Setter
    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Setter
    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold;

    /**
     * Optimistic-lock version: Hibernate adds "WHERE version = ?" to every UPDATE and increments it. A concurrent
     * writer that read an older version gets an ObjectOptimisticLockingFailureException (mapped to 409).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Inventory(Long productId, String sku, int availableQuantity, int lowStockThreshold) {
        if (availableQuantity < 0 || lowStockThreshold < 0) {
            throw new IllegalArgumentException("Quantities must not be negative");
        }
        this.productId = productId;
        this.sku = sku;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = 0;
        this.lowStockThreshold = lowStockThreshold;
    }

    public boolean canReserve(int quantity) {
        return availableQuantity >= quantity;
    }

    /** available -> reserved. Callers must have checked {@link #canReserve(int)} while holding the row lock. */
    public void reserve(int quantity) {
        requirePositive(quantity);
        if (!canReserve(quantity)) {
            throw new IllegalStateException("Cannot reserve " + quantity + " of product " + productId
                    + ", only " + availableQuantity + " available");
        }
        availableQuantity -= quantity;
        reservedQuantity += quantity;
    }

    /** reserved -> sold (leaves the warehouse). */
    public void commitReserved(int quantity) {
        requirePositive(quantity);
        reservedQuantity = Math.max(0, reservedQuantity - quantity);
    }

    /** reserved -> available (order cancelled / reservation expired before it was committed). */
    public void releaseReserved(int quantity) {
        requirePositive(quantity);
        reservedQuantity = Math.max(0, reservedQuantity - quantity);
        availableQuantity += quantity;
    }

    /** Sold goods come back (order cancelled AFTER commit) -> available again. */
    public void restock(int quantity) {
        requirePositive(quantity);
        availableQuantity += quantity;
    }

    /** Directly sells from available stock (used when an expired reservation must be re-acquired at commit time). */
    public void consumeAvailable(int quantity) {
        requirePositive(quantity);
        if (!canReserve(quantity)) {
            throw new IllegalStateException("Cannot consume " + quantity + " of product " + productId);
        }
        availableQuantity -= quantity;
    }

    /** Admin correction of the sellable quantity (e.g. after a stock count). Reserved stock is not touched. */
    public void setAvailableQuantity(int availableQuantity) {
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("availableQuantity must not be negative");
        }
        this.availableQuantity = availableQuantity;
    }

    public boolean isInStock() {
        return availableQuantity > 0;
    }

    public boolean isLowStock() {
        return availableQuantity <= lowStockThreshold;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive but was " + quantity);
        }
    }
}
