package com.philips.ecommerce.inventory.entity;

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

/** One product line of a reservation. Duplicate product lines are merged before saving (unique per reservation). */
@Entity
@Table(name = "stock_reservation_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_reservation_item_product",
                columnNames = {"reservation_id", "product_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LAZY: the default EAGER of @ManyToOne would load the parent again for every item. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private StockReservation reservation;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    StockReservationItem(StockReservation reservation, Long productId, int quantity) {
        this.reservation = reservation;
        this.productId = productId;
        this.quantity = quantity;
    }
}
