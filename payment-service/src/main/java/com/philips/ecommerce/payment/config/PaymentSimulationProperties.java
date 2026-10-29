package com.philips.ecommerce.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Binds {@code app.payment.simulation.*} - knobs of the SIMULATED gateway (there is no real payment provider).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.payment.simulation")
public class PaymentSimulationProperties {

    /** Amounts strictly above this value are declined with AMOUNT_LIMIT_EXCEEDED. */
    private BigDecimal maxAmount = new BigDecimal("5000.00");

    /**
     * How long {@code tok_timeout} blocks before succeeding. Deliberately longer than order-service's 3 s Feign read
     * timeout to demonstrate "caller timed out, but the payment still went through" (unknown outcome).
     */
    private long timeoutDelayMs = 6000;

    /** Probability (0.0 - 1.0) that an otherwise valid payment is declined; for chaos experiments. */
    private double randomFailureRate = 0.0;
}
