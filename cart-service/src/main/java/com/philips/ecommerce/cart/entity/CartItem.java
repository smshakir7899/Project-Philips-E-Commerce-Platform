package com.philips.ecommerce.cart.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One product line. sku, productName and unitPrice are a SNAPSHOT copied from product-service when the line is
 * added or updated: the cart can be displayed without calling product-service, and the customer sees the price
 * they chose. The snapshot is not authoritative - order-service re-reads current prices at checkout.
 */
@Entity
@Table(name = "cart_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_cart_item_product", columnNames = {"cart_id", "product_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    CartItem(Cart cart, Long productId, String sku, String productName, BigDecimal unitPrice, int quantity) {
        this.cart = cart;
        this.productId = productId;
        refreshSnapshot(sku, productName, unitPrice);
        changeQuantity(quantity);
    }

    public void changeQuantity(int newQuantity) {
        if (newQuantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
        this.quantity = newQuantity;
    }

    public void refreshSnapshot(String newSku, String newProductName, BigDecimal newUnitPrice) {
        this.sku = newSku;
        this.productName = newProductName;
        this.unitPrice = newUnitPrice.setScale(2, RoundingMode.HALF_UP);
    }

    /** unitPrice x quantity, scale 2, HALF_UP (money is never a double). */
    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }
}
