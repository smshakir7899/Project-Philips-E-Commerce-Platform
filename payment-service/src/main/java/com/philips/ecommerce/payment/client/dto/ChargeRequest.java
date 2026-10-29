package com.philips.ecommerce.payment.client.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;

/** What we send to the payment gateway. The card token lives only in memory for the duration of the call. */
@Getter
@AllArgsConstructor
@ToString(exclude = "cardToken")
public class ChargeRequest {
    private final String paymentReference;
    private final BigDecimal amount;
    private final String currency;
    private final String cardToken;
}
