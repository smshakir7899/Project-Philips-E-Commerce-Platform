package com.philips.ecommerce.payment.kafka;

import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.event.PaymentRefundedEvent;
import com.philips.ecommerce.payment.entity.CancelledOrder;
import com.philips.ecommerce.payment.entity.Payment;
import com.philips.ecommerce.payment.entity.ProcessedEvent;
import com.philips.ecommerce.payment.outbox.OutboxService;
import com.philips.ecommerce.payment.repository.CancelledOrderRepository;
import com.philips.ecommerce.payment.repository.PaymentRepository;
import com.philips.ecommerce.payment.repository.ProcessedEventRepository;
import com.philips.ecommerce.payment.service.PaymentEventFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Saga compensation in payment-service: reacts to order-cancelled by voiding/refunding the order's payments.
 * <ul>
 *   <li>SUCCEEDED -> REFUNDED + payment-refunded (money was captured for an order that will not ship)</li>
 *   <li>PROCESSING -> cancelRequested = true (gateway call still running; the late success is refunded by
 *       PaymentStateService.complete)</li>
 *   <li>FAILED / REFUNDED -> nothing to do</li>
 *   <li>no payment at all -> a {@link CancelledOrder} tombstone is stored, so a payment request for this order that
 *       arrives LATER (order-service timed out and cancelled while its request was still in flight) is refused
 *       instead of charging the customer. For INSUFFICIENT_STOCK/INVENTORY_ERROR no request will ever come; the
 *       tiny row is harmless.</li>
 * </ul>
 * Idempotent consumer: the processed_event marker is checked and written in the SAME transaction as the payment
 * change, so a redelivered event (at-least-once delivery, consumer rebalance, outbox re-send) has no second effect.
 */
@Service
public class OrderCancellationHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationHandler.class);

    private final PaymentRepository paymentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final CancelledOrderRepository cancelledOrderRepository;
    private final OutboxService outboxService;
    private final PaymentEventFactory eventFactory;
    private final Clock clock;

    public OrderCancellationHandler(PaymentRepository paymentRepository,
                                    ProcessedEventRepository processedEventRepository,
                                    CancelledOrderRepository cancelledOrderRepository,
                                    OutboxService outboxService, PaymentEventFactory eventFactory, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.processedEventRepository = processedEventRepository;
        this.cancelledOrderRepository = cancelledOrderRepository;
        this.outboxService = outboxService;
        this.eventFactory = eventFactory;
        this.clock = clock;
    }

    /**
     * @return false if the event had already been processed (duplicate), true otherwise
     */
    @Transactional
    public boolean handle(OrderCancelledEvent event, String topic) {
        if (processedEventRepository.existsById(event.getEventId())) {
            log.info("Duplicate {} {} for order {} - skipped", OrderCancelledEvent.TYPE, event.getEventId(),
                    event.getOrderNumber());
            return false;
        }

        List<Payment> payments = paymentRepository.findByOrderNumberOrderByIdAsc(event.getOrderNumber());
        if (payments.isEmpty() && !cancelledOrderRepository.existsById(event.getOrderNumber())) {
            // Nothing to refund NOW - remember the cancellation so a late payment request is refused.
            cancelledOrderRepository.save(new CancelledOrder(event.getOrderNumber(),
                    String.valueOf(event.getReason()), Instant.now(clock)));
            log.info("Order {} cancelled ({}) before any payment arrived - tombstone stored", event.getOrderNumber(),
                    event.getReason());
        }
        for (Payment payment : payments) {
            compensate(payment, event);
        }

        // An optimistic-lock conflict with a concurrent gateway completion makes this transaction fail at commit;
        // the marker is rolled back with it and DefaultErrorHandler redelivers the event, which then sees the new
        // state (e.g. SUCCEEDED) and refunds it.
        processedEventRepository.save(new ProcessedEvent(event.getEventId(), OrderCancelledEvent.TYPE, topic,
                Instant.now(clock)));
        return true;
    }

    private void compensate(Payment payment, OrderCancelledEvent event) {
        switch (payment.getStatus()) {
            case SUCCEEDED:
                payment.markRefunded();
                String reason = "ORDER_CANCELLED:" + event.getReason();
                PaymentRefundedEvent refunded = eventFactory.refunded(payment, reason);
                outboxService.enqueue(KafkaTopics.PAYMENT_REFUNDED, payment.getOrderNumber(),
                        PaymentRefundedEvent.TYPE, refunded);
                log.info("Payment {} REFUNDED because order {} was cancelled ({})", payment.getPaymentReference(),
                        event.getOrderNumber(), event.getReason());
                break;
            case PROCESSING:
                payment.requestCancellation();
                log.info("Payment {} is still PROCESSING - flagged cancelRequested for order {}",
                        payment.getPaymentReference(), event.getOrderNumber());
                break;
            case FAILED:
            case REFUNDED:
            default:
                log.info("Payment {} is {} - nothing to compensate for order {}", payment.getPaymentReference(),
                        payment.getStatus(), event.getOrderNumber());
                break;
        }
    }
}
