package com.philips.ecommerce.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * Body of POST /api/orders/checkout. The items are NOT sent by the client: they are read from the customer's cart and
 * priced from product-service, so a client cannot tamper with prices.
 * The card token is forwarded to payment-service and never stored here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRequest {
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9-]{3,64}$")
    private String customerId;

    @NotBlank
    @Size(max = 100)
    private String cardToken;

    @Valid
    @NotNull
    private AddressDto shippingAddress;
}
