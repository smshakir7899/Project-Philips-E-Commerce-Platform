package com.philips.ecommerce.order.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** payment-service's PaymentResponse. Status kept as String so an unknown future status cannot break decoding. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentDto {
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String PROCESSING = "PROCESSING";

    private String paymentReference;
    private String orderNumber;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
}
