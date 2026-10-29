package com.philips.ecommerce.order.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Embeddable;

/**
 * Value object embedded in the orders table (no own identity / table). The address is copied into the order on
 * purpose: later changes to the customer's address book must not rewrite where an old order was shipped.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ShippingAddress {

    @Column(name = "ship_full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "ship_line1", nullable = false, length = 255)
    private String line1;

    @Column(name = "ship_city", nullable = false, length = 100)
    private String city;

    @Column(name = "ship_postal_code", nullable = false, length = 20)
    private String postalCode;

    @Column(name = "ship_country", nullable = false, length = 100)
    private String country;
}
