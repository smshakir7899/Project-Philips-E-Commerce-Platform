package com.philips.ecommerce.payment.client;

import com.philips.ecommerce.payment.client.dto.ChargeRequest;
import com.philips.ecommerce.payment.client.dto.ChargeResult;
import com.philips.ecommerce.payment.exception.PaymentGatewayException;

/**
 * Port to an external payment provider. The only implementation in this project is
 * {@link SimulatedPaymentGateway}; a real provider adapter (Stripe, Adyen...) would implement the same interface.
 */
public interface PaymentGateway {

    /**
     * Charges the card. May block (network call).
     *
     * @return approved or declined result
     * @throws PaymentGatewayException on technical failure (outcome unknown / provider error)
     */
    ChargeResult charge(ChargeRequest request);
}
