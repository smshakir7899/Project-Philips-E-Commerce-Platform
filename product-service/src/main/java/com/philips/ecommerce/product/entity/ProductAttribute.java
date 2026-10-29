package com.philips.ecommerce.product.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

/**
 * Free-form specification ("Battery life" = "120 min"). Columns are named attr_name / attr_value because
 * VALUE is a reserved word in H2 2.x (used by the tests) and a keyword in MySQL.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "product_attribute")
public class ProductAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "attr_name", nullable = false, length = 100)
    private String name;

    @Column(name = "attr_value", nullable = false, length = 500)
    private String value;

    public ProductAttribute(Product product, String name, String value) {
        this.product = product;
        this.name = name;
        this.value = value;
    }
}
