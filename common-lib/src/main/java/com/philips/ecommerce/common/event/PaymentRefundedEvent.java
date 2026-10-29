package com.philips.ecommerce.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** Topic: {@link KafkaTopics#PAYMENT_REFUNDED}. Key: orderNumber. Producer: payment-service (via outbox). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRefundedEvent {
    public static final String TYPE = "PaymentRefundedEvent";

    private String eventId;
    private Instant occurredAt;
    private String paymentReference;
    private String orderNumber;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    private String reason;
}
