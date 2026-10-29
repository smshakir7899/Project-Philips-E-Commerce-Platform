package com.philips.ecommerce.order.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Body of POST /api/payments. idempotencyKey = "PAY-" + orderNumber makes retries of this call safe. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequestDto {
    private String orderNumber;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    private String cardToken;
    private String idempotencyKey;
}
