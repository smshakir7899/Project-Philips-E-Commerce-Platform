package com.philips.ecommerce.cart.mapper;

import com.philips.ecommerce.cart.dto.CartItemResponse;
import com.philips.ecommerce.cart.dto.CartResponse;
import com.philips.ecommerce.cart.entity.Cart;
import com.philips.ecommerce.cart.entity.CartItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Entity -> API mapping, including the derived totals (never stored, always computed from the lines). */
public final class CartMapper {

    private CartMapper() {
    }

    public static CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        int totalQuantity = 0;
        for (CartItem item : cart.getItems()) {
            BigDecimal lineTotal = item.lineTotal();
            items.add(CartItemResponse.builder()
                    .productId(item.getProductId())
                    .sku(item.getSku())
                    .productName(item.getProductName())
                    .unitPrice(item.getUnitPrice())
                    .quantity(item.getQuantity())
                    .lineTotal(lineTotal)
                    .build());
            subtotal = subtotal.add(lineTotal);
            totalQuantity += item.getQuantity();
        }
        return CartResponse.builder()
                .customerId(cart.getCustomerId())
                .items(items)
                .totalQuantity(totalQuantity)
                .subtotal(subtotal.setScale(2, RoundingMode.HALF_UP))
                .currency(cart.getCurrency())
                .updatedAt(cart.getUpdatedAt())
                .build();
    }

    /** GET of a customer without a cart returns an empty cart rather than 404 (simpler for every client). */
    public static CartResponse empty(String customerId, String currency) {
        return CartResponse.builder()
                .customerId(customerId)
                .items(Collections.<CartItemResponse>emptyList())
                .totalQuantity(0)
                .subtotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .currency(currency)
                .build();
    }
}
