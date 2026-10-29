package com.philips.ecommerce.order.service;

import com.philips.ecommerce.order.dto.OrderResponse;
import lombok.Getter;

/** Outcome of a checkout call: the order plus whether it was a replay of an earlier request with the same key. */
@Getter
public final class CheckoutResult {

    private final OrderResponse order;
    private final boolean replayed;

    private CheckoutResult(OrderResponse order, boolean replayed) {
        this.order = order;
        this.replayed = replayed;
    }

    public static CheckoutResult created(OrderResponse order) {
        return new CheckoutResult(order, false);
    }

    public static CheckoutResult replayed(OrderResponse order) {
        return new CheckoutResult(order, true);
    }
}
