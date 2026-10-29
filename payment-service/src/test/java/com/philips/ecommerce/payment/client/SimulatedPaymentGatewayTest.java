package com.philips.ecommerce.payment.client;

import com.philips.ecommerce.payment.client.dto.ChargeRequest;
import com.philips.ecommerce.payment.client.dto.ChargeResult;
import com.philips.ecommerce.payment.config.PaymentSimulationProperties;
import com.philips.ecommerce.payment.exception.PaymentGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedPaymentGatewayTest {

    private PaymentSimulationProperties properties;
    private SimulatedPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new PaymentSimulationProperties();
        properties.setMaxAmount(new BigDecimal("5000.00"));
        properties.setTimeoutDelayMs(100);
        gateway = new SimulatedPaymentGateway(properties, new Random(42));
    }

    private static ChargeRequest request(String token, String amount) {
        return new ChargeRequest("PAY-000000000001", new BigDecimal(amount), "EUR", token);
    }

    @Test
    void approvesRegularToken() {
        ChargeResult result = gateway.charge(request("tok_visa", "99.99"));

        assertThat(result.isApproved()).isTrue();
        assertThat(result.getFailureReason()).isNull();
        assertThat(result.getGatewayTransactionId()).startsWith("SIM-");
    }

    @ParameterizedTest
    @CsvSource({
            "tok_declined, 10.00, CARD_DECLINED",
            "tok_insufficient_funds, 10.00, INSUFFICIENT_FUNDS",
            "tok_visa, 5000.01, AMOUNT_LIMIT_EXCEEDED"
    })
    void declinesAccordingToSimulationRules(String token, String amount, String expectedReason) {
        ChargeResult result = gateway.charge(request(token, amount));

        assertThat(result.isApproved()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo(expectedReason);
    }

    @Test
    void amountEqualToLimitIsAccepted() {
        assertThat(gateway.charge(request("tok_visa", "5000.00")).isApproved()).isTrue();
    }

    @Test
    void timeoutTokenSleepsThenSucceeds() {
        long start = System.nanoTime();

        ChargeResult result = gateway.charge(request("tok_timeout", "10.00"));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result.isApproved()).isTrue();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(100);
    }

    @Test
    void errorTokenThrowsGatewayException() {
        assertThatThrownBy(() -> gateway.charge(request("tok_error", "10.00")))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void randomFailureRateOfOneDeclinesEverything() {
        properties.setRandomFailureRate(1.0);

        ChargeResult result = gateway.charge(request("tok_visa", "10.00"));

        assertThat(result.isApproved()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo(SimulatedPaymentGateway.REASON_RANDOM_FAILURE);
    }
}
