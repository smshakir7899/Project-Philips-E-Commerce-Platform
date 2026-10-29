package com.philips.ecommerce.payment.client;

import com.philips.ecommerce.payment.client.dto.ChargeRequest;
import com.philips.ecommerce.payment.client.dto.ChargeResult;
import com.philips.ecommerce.payment.config.PaymentSimulationProperties;
import com.philips.ecommerce.payment.exception.PaymentGatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

/**
 * SIMULATED payment gateway - no money moves and no external system is called. Outcomes are driven by the card
 * token so that every saga branch can be demonstrated deterministically:
 * <ul>
 *   <li>{@code tok_declined} -> declined CARD_DECLINED</li>
 *   <li>{@code tok_insufficient_funds} -> declined INSUFFICIENT_FUNDS</li>
 *   <li>amount &gt; {@code max-amount} -> declined AMOUNT_LIMIT_EXCEEDED</li>
 *   <li>{@code tok_timeout} -> sleeps {@code timeout-delay-ms}, then approved (slower than the caller's timeout)</li>
 *   <li>{@code tok_error} -> {@link PaymentGatewayException} (503 PAYMENT_GATEWAY_ERROR)</li>
 *   <li>anything else (e.g. {@code tok_visa}) -> approved; unless {@code random-failure-rate} kicks in</li>
 * </ul>
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    public static final String TOKEN_DECLINED = "tok_declined";
    public static final String TOKEN_INSUFFICIENT_FUNDS = "tok_insufficient_funds";
    public static final String TOKEN_TIMEOUT = "tok_timeout";
    public static final String TOKEN_ERROR = "tok_error";

    public static final String REASON_CARD_DECLINED = "CARD_DECLINED";
    public static final String REASON_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String REASON_AMOUNT_LIMIT_EXCEEDED = "AMOUNT_LIMIT_EXCEEDED";
    public static final String REASON_RANDOM_FAILURE = "SIMULATED_RANDOM_FAILURE";

    private final PaymentSimulationProperties properties;
    private final Random random;

    @Autowired
    public SimulatedPaymentGateway(PaymentSimulationProperties properties) {
        this(properties, new Random());
    }

    /** Visible for tests: inject a seeded Random to make random failures reproducible. */
    SimulatedPaymentGateway(PaymentSimulationProperties properties, Random random) {
        this.properties = properties;
        this.random = random;
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        String token = request.getCardToken();
        log.info("SIMULATED gateway: charging {} {} for {}", request.getAmount(), request.getCurrency(),
                request.getPaymentReference());

        if (TOKEN_ERROR.equals(token)) {
            throw new PaymentGatewayException("Simulated gateway error for " + request.getPaymentReference());
        }
        if (TOKEN_TIMEOUT.equals(token)) {
            sleep(properties.getTimeoutDelayMs());
            return ChargeResult.approved(transactionId());
        }
        if (TOKEN_DECLINED.equals(token)) {
            return ChargeResult.declined(REASON_CARD_DECLINED);
        }
        if (TOKEN_INSUFFICIENT_FUNDS.equals(token)) {
            return ChargeResult.declined(REASON_INSUFFICIENT_FUNDS);
        }
        if (request.getAmount().compareTo(properties.getMaxAmount()) > 0) {
            return ChargeResult.declined(REASON_AMOUNT_LIMIT_EXCEEDED);
        }
        if (properties.getRandomFailureRate() > 0 && random.nextDouble() < properties.getRandomFailureRate()) {
            return ChargeResult.declined(REASON_RANDOM_FAILURE);
        }
        return ChargeResult.approved(transactionId());
    }

    private static String transactionId() {
        return "SIM-" + UUID.randomUUID();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // Restore the interrupt flag so the caller (e.g. a shutting-down container) can see it.
            Thread.currentThread().interrupt();
            throw new PaymentGatewayException("Interrupted while waiting for the simulated gateway", e);
        }
    }
}
