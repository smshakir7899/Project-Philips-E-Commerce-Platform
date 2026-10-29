package com.philips.ecommerce.payment.client.dto;

import lombok.Getter;
import lombok.ToString;

/** Business outcome of a charge: approved, or declined with a reason (technical errors are exceptions). */
@Getter
@ToString
public final class ChargeResult {

    private final boolean approved;
    private final String failureReason;
    private final String gatewayTransactionId;

    private ChargeResult(boolean approved, String failureReason, String gatewayTransactionId) {
        this.approved = approved;
        this.failureReason = failureReason;
        this.gatewayTransactionId = gatewayTransactionId;
    }

    public static ChargeResult approved(String gatewayTransactionId) {
        return new ChargeResult(true, null, gatewayTransactionId);
    }

    public static ChargeResult declined(String failureReason) {
        return new ChargeResult(false, failureReason, null);
    }
}
