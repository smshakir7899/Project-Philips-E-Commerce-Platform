package com.philips.ecommerce.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

/** POST /api/payments body. cardToken is excluded from toString() so it never ends up in logs. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "cardToken")
public class PaymentRequest {

    @NotBlank
    @Size(max = 32)
    private String orderNumber;

    @NotBlank
    @Size(max = 64)
    private String customerId;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal amount;

    @NotBlank
    @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code such as EUR")
    private String currency;

    @NotBlank
    @Size(max = 100)
    private String cardToken;

    @NotBlank
    @Size(max = 100)
    private String idempotencyKey;
}
