package com.philips.ecommerce.cart.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Aggregate root of a customer's cart (one cart per customerId). Items are only changed through this class;
 * cascade ALL + orphanRemoval make "remove from the list" a DELETE of the row.
 *
 * <p>Optimistic locking: every change calls {@link #touch()}, which updates the cart row itself, so Hibernate
 * increments {@link #version} even when only items changed. Two concurrent modifications of the same cart (two
 * browser tabs) therefore cannot overwrite each other silently: the second one fails with 409.
 */
@Entity
@Table(name = "cart", uniqueConstraints = @UniqueConstraint(name = "uk_cart_customer", columnNames = "customer_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<CartItem> items = new ArrayList<>();

    public Cart(String customerId, String currency) {
        this.customerId = customerId;
        this.currency = currency;
    }

    public Optional<CartItem> findItem(Long productId) {
        for (CartItem item : items) {
            if (item.getProductId().equals(productId)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    public CartItem addItem(Long productId, String sku, String productName, BigDecimal unitPrice, int quantity) {
        CartItem item = new CartItem(this, productId, sku, productName, unitPrice, quantity);
        items.add(item);
        return item;
    }

    public boolean removeItem(Long productId) {
        Iterator<CartItem> iterator = items.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getProductId().equals(productId)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    public void clearItems() {
        items.clear();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** The cart adopts the currency of its first product; later products must use the same currency. */
    public void adoptCurrency(String newCurrency) {
        if (!isEmpty()) {
            throw new IllegalStateException("Currency can only change while the cart is empty");
        }
        this.currency = newCurrency;
    }

    /** Marks the aggregate as modified (see class comment on optimistic locking). */
    public void touch() {
        updatedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }
}
