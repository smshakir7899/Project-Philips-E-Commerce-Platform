package com.philips.ecommerce.order.service;

import com.philips.ecommerce.order.dto.AddressDto;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/** Everything OrderStateService needs to persist a new order (validated and priced by CheckoutService). */
@Getter
@AllArgsConstructor
public class NewOrderCommand {
    private final String customerId;
    private final String idempotencyKey;
    private final String currency;
    private final AddressDto shippingAddress;
    private final List<OrderLine> lines;
}
