package com.philips.ecommerce.payment.kafka;

import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.payment.config.ClockConfig;
import com.philips.ecommerce.payment.entity.Payment;
import com.philips.ecommerce.payment.entity.PaymentStatus;
import com.philips.ecommerce.payment.outbox.OutboxEvent;
import com.philips.ecommerce.payment.outbox.OutboxEventRepository;
import com.philips.ecommerce.payment.outbox.OutboxService;
import com.philips.ecommerce.payment.repository.CancelledOrderRepository;
import com.philips.ecommerce.payment.repository.PaymentRepository;
import com.philips.ecommerce.payment.repository.ProcessedEventRepository;
import com.philips.ecommerce.payment.service.PaymentEventFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({OrderCancellationHandler.class, OutboxService.class, PaymentEventFactory.class, ClockConfig.class,
        JacksonAutoConfiguration.class})
class OrderCancellationHandlerTest {

    @Autowired
    private OrderCancellationHandler handler;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @Autowired
    private OutboxEventRepository outboxRepository;
    @Autowired
    private CancelledOrderRepository cancelledOrderRepository;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAll();
        processedEventRepository.deleteAll();
        paymentRepository.deleteAll();
        cancelledOrderRepository.deleteAll();
    }

    private Payment storePayment(String reference, String orderNumber, PaymentStatus status) {
        Payment payment = new Payment(reference, orderNumber, "CUST-1001", new BigDecimal("25.00"), "EUR",
                "tok_visa", "PAY-" + orderNumber + "-" + reference);
        if (status == PaymentStatus.SUCCEEDED || status == PaymentStatus.REFUNDED) {
            payment.markSucceeded();
        }
        if (status == PaymentStatus.REFUNDED) {
            payment.markRefunded();
        }
        if (status == PaymentStatus.FAILED) {
            payment.markFailed("CARD_DECLINED");
        }
        return paymentRepository.save(payment);
    }

    private static OrderCancelledEvent event(String orderNumber, CancellationReason reason) {
        return OrderCancelledEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .orderNumber(orderNumber)
                .customerId("CUST-1001")
                .previousStatus("CONFIRMED")
                .reason(reason)
                .build();
    }

    @Test
    void succeededPaymentIsRefundedAndRefundEventEnqueued() {
        storePayment("PAY-000000000001", "ORD-1", PaymentStatus.SUCCEEDED);

        boolean processed = handler.handle(event("ORD-1", CancellationReason.CUSTOMER_REQUESTED),
                KafkaTopics.ORDER_CANCELLED);

        assertThat(processed).isTrue();
        assertThat(paymentRepository.findByPaymentReference("PAY-000000000001").get().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        List<OutboxEvent> outbox = outboxRepository.findAll();
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).getTopic()).isEqualTo(KafkaTopics.PAYMENT_REFUNDED);
        assertThat(outbox.get(0).getPayload()).contains("ORDER_CANCELLED:CUSTOMER_REQUESTED");
    }

    @Test
    void duplicateEventHasNoSecondEffect() {
        storePayment("PAY-000000000002", "ORD-2", PaymentStatus.SUCCEEDED);
        OrderCancelledEvent event = event("ORD-2", CancellationReason.CUSTOMER_REQUESTED);

        assertThat(handler.handle(event, KafkaTopics.ORDER_CANCELLED)).isTrue();
        assertThat(handler.handle(event, KafkaTopics.ORDER_CANCELLED)).isFalse();

        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isEqualTo(1);
        assertThat(processedEventRepository.findById(event.getEventId())).isPresent();
    }

    @Test
    void processingPaymentIsFlaggedForCancellation() {
        storePayment("PAY-000000000003", "ORD-3", PaymentStatus.PROCESSING);

        handler.handle(event("ORD-3", CancellationReason.PAYMENT_ERROR), KafkaTopics.ORDER_CANCELLED);

        Payment payment = paymentRepository.findByPaymentReference("PAY-000000000003").get();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.isCancelRequested()).isTrue();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void failedAndRefundedPaymentsAreLeftUntouched() {
        storePayment("PAY-000000000004", "ORD-4", PaymentStatus.FAILED);
        storePayment("PAY-000000000005", "ORD-4", PaymentStatus.REFUNDED);

        assertThat(handler.handle(event("ORD-4", CancellationReason.PAYMENT_DECLINED), KafkaTopics.ORDER_CANCELLED))
                .isTrue();

        assertThat(paymentRepository.findByPaymentReference("PAY-000000000004").get().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentRepository.findByPaymentReference("PAY-000000000005").get().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(outboxRepository.count()).isZero();
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    void cancellationWithoutPaymentIsRecordedAsProcessed() {
        OrderCancelledEvent event = event("ORD-5", CancellationReason.INSUFFICIENT_STOCK);

        assertThat(handler.handle(event, KafkaTopics.ORDER_CANCELLED)).isTrue();

        assertThat(outboxRepository.count()).isZero();
        assertThat(processedEventRepository.findById(event.getEventId())).isPresent();
        // Tombstone: a payment request for ORD-5 arriving later will be refused.
        assertThat(cancelledOrderRepository.findById("ORD-5")).hasValueSatisfying(
                t -> assertThat(t.getReason()).isEqualTo("INSUFFICIENT_STOCK"));
    }

    @Test
    void secondCancellationEventKeepsTheExistingTombstone() {
        handler.handle(event("ORD-6", CancellationReason.PAYMENT_ERROR), KafkaTopics.ORDER_CANCELLED);

        // A different event id for the same order (e.g. re-sent by an operator) must not fail on the primary key.
        assertThat(handler.handle(event("ORD-6", CancellationReason.PAYMENT_ERROR), KafkaTopics.ORDER_CANCELLED))
                .isTrue();

        assertThat(cancelledOrderRepository.count()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isEqualTo(2);
    }

    @Test
    void noTombstoneWhenAPaymentExists() {
        storePayment("PAY-000000000007", "ORD-7", PaymentStatus.PROCESSING);

        handler.handle(event("ORD-7", CancellationReason.PAYMENT_ERROR), KafkaTopics.ORDER_CANCELLED);

        assertThat(cancelledOrderRepository.existsById("ORD-7")).isFalse();
    }
}
