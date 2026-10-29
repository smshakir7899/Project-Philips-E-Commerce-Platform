package com.philips.ecommerce.order.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;

/** A priced cart line, ready to become an OrderItem (prices come from product-service, not from the cart). */
@Getter
@ToString
@AllArgsConstructor
public class OrderLine {
    private final Long productId;
    private final String sku;
    private final String productName;
    private final BigDecimal unitPrice;
    private final int quantity;
}
