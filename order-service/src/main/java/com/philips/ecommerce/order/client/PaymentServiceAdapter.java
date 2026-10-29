package com.philips.ecommerce.order.client;

import com.philips.ecommerce.order.client.dto.PaymentDto;
import com.philips.ecommerce.order.client.dto.PaymentRequestDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

/**
 * Resilience wrapper around {@link PaymentClient}.
 * <p>
 * Retrying a payment is safe because the request carries {@code idempotencyKey = "PAY-" + orderNumber}: if the
 * first attempt timed out but is still running in payment-service, the retry does NOT charge again - it gets the
 * stored payment back (typically still PROCESSING), which the saga treats as "outcome unknown" (PAYMENT_ERROR).
 * The read timeout (3 s) is shorter than the simulated slow gateway (tok_timeout, 6 s) to demonstrate exactly that.
 */
@Component
public class PaymentServiceAdapter {

    static final String INSTANCE = "payment-service";

    private final PaymentClient paymentClient;

    public PaymentServiceAdapter(PaymentClient paymentClient) {
        this.paymentClient = paymentClient;
    }

    @Retry(name = INSTANCE, fallbackMethod = "payFallback")
    @CircuitBreaker(name = INSTANCE)
    public PaymentDto pay(PaymentRequestDto request) {
        return paymentClient.pay(request);
    }

    @SuppressWarnings("unused")
    private PaymentDto payFallback(PaymentRequestDto request, Throwable failure) {
        throw RemoteFailures.translate(failure, "PAYMENT_SERVICE_UNAVAILABLE", INSTANCE);
    }
}
