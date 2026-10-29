package com.philips.ecommerce.payment.service;

import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.payment.client.PaymentGateway;
import com.philips.ecommerce.payment.client.dto.ChargeRequest;
import com.philips.ecommerce.payment.client.dto.ChargeResult;
import com.philips.ecommerce.payment.dto.PaymentProcessingResult;
import com.philips.ecommerce.payment.dto.PaymentRequest;
import com.philips.ecommerce.payment.dto.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Orchestrates one payment: idempotency check -> cancelled-order check -> TX1 PROCESSING -> gateway call
 * (NO transaction) -> TX2 outcome.
 * <p>
 * Deliberately NOT {@code @Transactional}: the gateway call can take seconds ({@code tok_timeout} takes 6 s). Holding
 * a DB transaction across it would pin a pooled connection and the row lock for that long, and the PROCESSING row
 * would be invisible to a concurrent retry (uncommitted) - which is exactly what makes the idempotency check work.
 * <p>
 * Remember this is a SIMULATED gateway (see {@link com.philips.ecommerce.payment.client.SimulatedPaymentGateway}).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** How often a state change is re-attempted after losing an optimistic-locking race. */
    static final int OPTIMISTIC_LOCK_ATTEMPTS = 3;

    private final PaymentStateService stateService;
    private final PaymentGateway gateway;

    public PaymentService(PaymentStateService stateService, PaymentGateway gateway) {
        this.stateService = stateService;
        this.gateway = gateway;
    }

    public PaymentProcessingResult process(PaymentRequest request) {
        // 1. Idempotency: a known key returns the stored payment (whatever its status, even PROCESSING).
        Optional<PaymentResponse> existing = stateService.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent replay of payment {} (key {})", existing.get().getPaymentReference(),
                    request.getIdempotencyKey());
            return new PaymentProcessingResult(existing.get(), false);
        }

        // 2. The order may already be cancelled: order-service timed out and published order-cancelled while this
        //    request was still in flight, and the consumer stored a tombstone. Never charge such an order:
        //    record a FAILED attempt (201, payment-processed FAILED) without calling the gateway.
        if (stateService.isOrderCancelled(request.getOrderNumber())) {
            log.info("Order {} is already cancelled - payment refused without calling the gateway",
                    request.getOrderNumber());
            return insertOrReplay(request, () -> stateService.createRejectedForCancelledOrder(request));
        }

        // 3. TX1: insert PROCESSING.
        PaymentProcessingResult inserted = insertOrReplay(request, () -> stateService.createProcessing(request));
        if (!inserted.isCreated()) {
            return inserted;
        }
        PaymentResponse processing = inserted.getPayment();
        final String reference = processing.getPaymentReference();

        // 4. Gateway call outside any transaction.
        final ChargeResult result;
        try {
            result = gateway.charge(new ChargeRequest(reference, processing.getAmount(), processing.getCurrency(),
                    request.getCardToken()));
        } catch (RuntimeException e) {
            // PaymentGatewayException (or anything unexpected): record the failed attempt + payment-processed FAILED,
            // then tell the caller 503 so order-service maps it to PAYMENT_SERVICE_UNAVAILABLE.
            withOptimisticRetry(() -> stateService.recordGatewayError(reference, e.getMessage()));
            throw new ServiceUnavailableException("PAYMENT_GATEWAY_ERROR",
                    "The payment gateway failed to process payment " + reference, e)
                    .withDetail("paymentReference", reference);
        }

        // 5. TX2: store the outcome (+ immediate refund of a success if the order was cancelled meanwhile - flagged
        //    by the consumer or, for the race with step 2, found as a tombstone).
        PaymentResponse completed = withOptimisticRetry(() -> stateService.complete(reference, result));
        return new PaymentProcessingResult(completed, true);
    }

    /**
     * Runs an inserting step. Two concurrent first requests with the same key both pass the idempotency check; the
     * UNIQUE constraint on idempotency_key lets only one insert win - the loser replays the winner's row (created =
     * false, i.e. HTTP 200).
     */
    private PaymentProcessingResult insertOrReplay(PaymentRequest request, Supplier<PaymentResponse> insert) {
        try {
            return new PaymentProcessingResult(insert.get(), true);
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent request with idempotency key {} - replaying the stored payment",
                    request.getIdempotencyKey());
            return stateService.findByIdempotencyKey(request.getIdempotencyKey())
                    .map(p -> new PaymentProcessingResult(p, false))
                    .orElseThrow(() -> e);
        }
    }

    public PaymentResponse getByReference(String paymentReference) {
        return stateService.getByReference(paymentReference);
    }

    public PaymentResponse getLatestForOrder(String orderNumber) {
        return stateService.getLatestForOrder(orderNumber);
    }

    public PaymentResponse refund(String paymentReference) {
        return stateService.refund(paymentReference, PaymentStateService.REFUND_REASON_MANUAL);
    }

    /**
     * Re-runs a transactional step when it lost an optimistic-locking race - typically against the order-cancelled
     * consumer setting {@code cancelRequested} on the same row. Each attempt is a NEW transaction that re-reads the
     * row, so the retry sees the consumer's change (and refunds a late success) instead of overwriting it.
     */
    private PaymentResponse withOptimisticRetry(Supplier<PaymentResponse> step) {
        ObjectOptimisticLockingFailureException last = null;
        for (int attempt = 1; attempt <= OPTIMISTIC_LOCK_ATTEMPTS; attempt++) {
            try {
                return step.get();
            } catch (ObjectOptimisticLockingFailureException e) {
                last = e;
                log.info("Optimistic lock conflict (attempt {}/{}), re-reading payment", attempt,
                        OPTIMISTIC_LOCK_ATTEMPTS);
            }
        }
        throw last;
    }
}
