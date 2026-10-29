package com.philips.ecommerce.payment.service;

import com.philips.ecommerce.common.event.PaymentProcessedEvent;
import com.philips.ecommerce.common.event.PaymentRefundedEvent;
import com.philips.ecommerce.common.event.PaymentResultStatus;
import com.philips.ecommerce.payment.entity.Payment;
import com.philips.ecommerce.payment.entity.PaymentStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Builds the Kafka event payloads (common-lib contracts) from a Payment. */
@Component
public class PaymentEventFactory {

    private final Clock clock;

    public PaymentEventFactory(Clock clock) {
        this.clock = clock;
    }

    public PaymentProcessedEvent processed(Payment payment, PaymentResultStatus result) {
        return PaymentProcessedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now(clock))
                .paymentReference(payment.getPaymentReference())
                .orderNumber(payment.getOrderNumber())
                .customerId(payment.getCustomerId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(result)
                .failureReason(result == PaymentResultStatus.FAILED ? payment.getFailureReason() : null)
                .build();
    }

    public PaymentProcessedEvent processed(Payment payment) {
        PaymentResultStatus result = payment.getStatus() == PaymentStatus.FAILED
                ? PaymentResultStatus.FAILED : PaymentResultStatus.SUCCEEDED;
        return processed(payment, result);
    }

    public PaymentRefundedEvent refunded(Payment payment, String reason) {
        return PaymentRefundedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now(clock))
                .paymentReference(payment.getPaymentReference())
                .orderNumber(payment.getOrderNumber())
                .customerId(payment.getCustomerId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .reason(reason)
                .build();
    }
}
