package com.philips.ecommerce.payment.service;

import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.PaymentProcessedEvent;
import com.philips.ecommerce.common.event.PaymentRefundedEvent;
import com.philips.ecommerce.common.event.PaymentResultStatus;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.payment.client.dto.ChargeResult;
import com.philips.ecommerce.payment.dto.PaymentRequest;
import com.philips.ecommerce.payment.dto.PaymentResponse;
import com.philips.ecommerce.payment.entity.Payment;
import com.philips.ecommerce.payment.entity.PaymentStatus;
import com.philips.ecommerce.payment.mapper.PaymentMapper;
import com.philips.ecommerce.payment.outbox.OutboxService;
import com.philips.ecommerce.payment.repository.CancelledOrderRepository;
import com.philips.ecommerce.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The LOCAL transactional steps of payment processing. Each public method is one short database transaction.
 * <p>
 * Why a separate bean instead of private methods of {@link PaymentService}? {@code @Transactional} is implemented
 * by a Spring proxy; a call from one method to another inside the same object (self-invocation) bypasses the proxy,
 * so the annotation would silently do nothing. Putting the steps on their own bean makes every call go through
 * the proxy, and lets {@link PaymentService} call the (slow) gateway BETWEEN transactions without holding a DB
 * connection or row locks while waiting.
 */
@Service
public class PaymentStateService {

    private static final Logger log = LoggerFactory.getLogger(PaymentStateService.class);

    public static final String REASON_GATEWAY_ERROR = "GATEWAY_ERROR";
    public static final String REFUND_REASON_MANUAL = "MANUAL_REFUND";
    public static final String REFUND_REASON_LATE_SUCCESS = "ORDER_CANCELLED_DURING_PROCESSING";
    public static final String REASON_ORDER_ALREADY_CANCELLED = "ORDER_ALREADY_CANCELLED";

    private final PaymentRepository paymentRepository;
    private final CancelledOrderRepository cancelledOrderRepository;
    private final OutboxService outboxService;
    private final PaymentEventFactory eventFactory;
    private final PaymentReferenceGenerator referenceGenerator;
    private final PaymentMapper mapper;

    public PaymentStateService(PaymentRepository paymentRepository, CancelledOrderRepository cancelledOrderRepository,
                               OutboxService outboxService, PaymentEventFactory eventFactory,
                               PaymentReferenceGenerator referenceGenerator, PaymentMapper mapper) {
        this.paymentRepository = paymentRepository;
        this.cancelledOrderRepository = cancelledOrderRepository;
        this.outboxService = outboxService;
        this.eventFactory = eventFactory;
        this.referenceGenerator = referenceGenerator;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentResponse> findByIdempotencyKey(String idempotencyKey) {
        return paymentRepository.findByIdempotencyKey(idempotencyKey).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getByReference(String paymentReference) {
        return mapper.toResponse(load(paymentReference));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getLatestForOrder(String orderNumber) {
        return paymentRepository.findFirstByOrderNumberOrderByCreatedAtDescIdDesc(orderNumber)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("PAYMENT_NOT_FOUND",
                        "No payment found for order " + orderNumber));
    }

    /** True if order-cancelled for this order was consumed before any payment existed (tombstone). */
    @Transactional(readOnly = true)
    public boolean isOrderCancelled(String orderNumber) {
        return cancelledOrderRepository.existsById(orderNumber);
    }

    /**
     * Records a payment request for an already-cancelled order as FAILED ORDER_ALREADY_CANCELLED (the gateway is
     * never called) and announces it with payment-processed FAILED. Storing the attempt - instead of just answering
     * 4xx - keeps the request idempotent: a retry with the same key gets this same row back.
     */
    @Transactional
    public PaymentResponse createRejectedForCancelledOrder(PaymentRequest request) {
        Payment payment = new Payment(referenceGenerator.next(), request.getOrderNumber(), request.getCustomerId(),
                request.getAmount(), request.getCurrency(), request.getCardToken(), request.getIdempotencyKey());
        payment.markFailed(REASON_ORDER_ALREADY_CANCELLED);
        paymentRepository.saveAndFlush(payment);
        enqueueProcessed(payment, PaymentResultStatus.FAILED);
        log.info("Payment {} refused: order {} was already cancelled", payment.getPaymentReference(),
                payment.getOrderNumber());
        return mapper.toResponse(payment);
    }

    /**
     * Step 1: record the attempt as PROCESSING and COMMIT before calling the gateway. A duplicate request arriving
     * while the gateway call is running then finds this row by idempotency key and does not charge again.
     * <p>
     * saveAndFlush forces the INSERT now, so a concurrent duplicate (same idempotency_key) fails inside this
     * method with DataIntegrityViolationException, which PaymentService turns into a replay.
     */
    @Transactional
    public PaymentResponse createProcessing(PaymentRequest request) {
        Payment payment = new Payment(referenceGenerator.next(), request.getOrderNumber(), request.getCustomerId(),
                request.getAmount(), request.getCurrency(), request.getCardToken(), request.getIdempotencyKey());
        paymentRepository.saveAndFlush(payment);
        log.info("Payment {} for order {} is PROCESSING", payment.getPaymentReference(), payment.getOrderNumber());
        return mapper.toResponse(payment);
    }

    /**
     * Step 3: store the gateway outcome and enqueue payment-processed - atomically, thanks to the outbox.
     * <p>
     * Late-success compensation: if order-cancelled arrived while the gateway call was running, the consumer only
     * set {@code cancelRequested} (there was nothing to refund yet). Now that the money is actually captured we
     * refund immediately, in the same transaction, and publish payment-refunded after payment-processed.
     * <p>
     * The tombstone is re-checked here as well: if the order-cancelled consumer ran concurrently with step 1 (it saw
     * no payment yet, while step 1 had not seen its tombstone yet), the tombstone is the only trace of the
     * cancellation. This transaction starts after both committed, so it sees it.
     */
    @Transactional
    public PaymentResponse complete(String paymentReference, ChargeResult result) {
        Payment payment = load(paymentReference);
        if (payment.getStatus() != PaymentStatus.PROCESSING) {
            // Defensive: completion must happen once; never overwrite a final state.
            log.warn("Payment {} already {} - ignoring gateway result {}", paymentReference, payment.getStatus(),
                    result);
            return mapper.toResponse(payment);
        }
        if (result.isApproved()) {
            payment.markSucceeded();
            enqueueProcessed(payment, PaymentResultStatus.SUCCEEDED);
            log.info("Payment {} SUCCEEDED (gateway tx {})", paymentReference, result.getGatewayTransactionId());
            if (!payment.isCancelRequested() && cancelledOrderRepository.existsById(payment.getOrderNumber())) {
                payment.requestCancellation();
            }
            if (payment.isCancelRequested()) {
                payment.markRefunded();
                enqueueRefunded(payment, REFUND_REASON_LATE_SUCCESS);
                log.info("Payment {} REFUNDED immediately: order {} was cancelled while the charge was running",
                        paymentReference, payment.getOrderNumber());
            }
        } else {
            payment.markFailed(result.getFailureReason());
            enqueueProcessed(payment, PaymentResultStatus.FAILED);
            log.info("Payment {} FAILED: {}", paymentReference, result.getFailureReason());
        }
        return mapper.toResponse(payment);
    }

    /** Gateway technical error: the attempt is recorded as FAILED GATEWAY_ERROR and announced like a decline. */
    @Transactional
    public PaymentResponse recordGatewayError(String paymentReference, String errorMessage) {
        Payment payment = load(paymentReference);
        if (payment.getStatus() != PaymentStatus.PROCESSING) {
            return mapper.toResponse(payment);
        }
        payment.markFailed(REASON_GATEWAY_ERROR);
        enqueueProcessed(payment, PaymentResultStatus.FAILED);
        log.warn("Payment {} FAILED with gateway error: {}", paymentReference, errorMessage);
        return mapper.toResponse(payment);
    }

    /** SUCCEEDED -> REFUNDED + payment-refunded; 409 INVALID_PAYMENT_STATE otherwise. */
    @Transactional
    public PaymentResponse refund(String paymentReference, String reason) {
        Payment payment = load(paymentReference);
        payment.markRefunded();
        enqueueRefunded(payment, reason);
        log.info("Payment {} REFUNDED ({})", paymentReference, reason);
        return mapper.toResponse(payment);
    }

    private Payment load(String paymentReference) {
        return paymentRepository.findByPaymentReference(paymentReference)
                .orElseThrow(() -> new ResourceNotFoundException("PAYMENT_NOT_FOUND",
                        "Payment " + paymentReference + " not found"));
    }

    private void enqueueProcessed(Payment payment, PaymentResultStatus result) {
        PaymentProcessedEvent event = eventFactory.processed(payment, result);
        outboxService.enqueue(KafkaTopics.PAYMENT_PROCESSED, payment.getOrderNumber(), PaymentProcessedEvent.TYPE,
                event);
    }

    private void enqueueRefunded(Payment payment, String reason) {
        PaymentRefundedEvent event = eventFactory.refunded(payment, reason);
        outboxService.enqueue(KafkaTopics.PAYMENT_REFUNDED, payment.getOrderNumber(), PaymentRefundedEvent.TYPE,
                event);
    }
}
