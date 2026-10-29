package com.philips.ecommerce.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.exception.BusinessException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.payment.client.PaymentGateway;
import com.philips.ecommerce.payment.client.dto.ChargeResult;
import com.philips.ecommerce.payment.config.ClockConfig;
import com.philips.ecommerce.payment.dto.PaymentProcessingResult;
import com.philips.ecommerce.payment.dto.PaymentRequest;
import com.philips.ecommerce.payment.entity.CancelledOrder;
import com.philips.ecommerce.payment.entity.Payment;
import com.philips.ecommerce.payment.entity.PaymentStatus;
import com.philips.ecommerce.payment.exception.PaymentGatewayException;
import com.philips.ecommerce.payment.kafka.OrderCancellationHandler;
import com.philips.ecommerce.payment.mapper.PaymentMapper;
import com.philips.ecommerce.payment.outbox.OutboxEvent;
import com.philips.ecommerce.payment.outbox.OutboxEventRepository;
import com.philips.ecommerce.payment.outbox.OutboxService;
import com.philips.ecommerce.payment.repository.CancelledOrderRepository;
import com.philips.ecommerce.payment.repository.PaymentRepository;
import com.philips.ecommerce.payment.repository.ProcessedEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentService against a real (H2) database with the real transactional steps; only the gateway is mocked.
 * The test-managed transaction is disabled (NOT_SUPPORTED) so each service step commits exactly like in production.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PaymentService.class, PaymentStateService.class, OutboxService.class, PaymentEventFactory.class,
        PaymentReferenceGenerator.class, PaymentMapper.class, OrderCancellationHandler.class, ClockConfig.class,
        JacksonAutoConfiguration.class})
class PaymentServiceTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private OrderCancellationHandler cancellationHandler;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OutboxEventRepository outboxRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @Autowired
    private CancelledOrderRepository cancelledOrderRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentGateway gateway;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAll();
        processedEventRepository.deleteAll();
        paymentRepository.deleteAll();
        cancelledOrderRepository.deleteAll();
    }

    private static OrderCancelledEvent cancelled(String orderNumber) {
        return OrderCancelledEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .orderNumber(orderNumber)
                .customerId("CUST-1001")
                .previousStatus("INVENTORY_RESERVED")
                .reason(CancellationReason.PAYMENT_ERROR)
                .build();
    }

    private static PaymentRequest request(String orderNumber, String token) {
        return PaymentRequest.builder()
                .orderNumber(orderNumber)
                .customerId("CUST-1001")
                .amount(new BigDecimal("149.90"))
                .currency("EUR")
                .cardToken(token)
                .idempotencyKey("PAY-" + orderNumber)
                .build();
    }

    @Test
    void newPaymentSucceedsAndEnqueuesPaymentProcessed() throws Exception {
        when(gateway.charge(any())).thenReturn(ChargeResult.approved("SIM-1"));

        PaymentProcessingResult result = paymentService.process(request("ORD-20260101-0000000A", "tok_visa"));

        assertThat(result.isCreated()).isTrue();
        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(result.getPayment().getPaymentReference()).matches("PAY-[0-9A-F]{12}");

        Payment stored = paymentRepository.findByPaymentReference(result.getPayment().getPaymentReference()).get();
        assertThat(stored.getCardTokenLast4()).isEqualTo("visa");

        List<OutboxEvent> outbox = outboxRepository.findAll();
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).getTopic()).isEqualTo(KafkaTopics.PAYMENT_PROCESSED);
        assertThat(outbox.get(0).getMessageKey()).isEqualTo("ORD-20260101-0000000A");
        JsonNode payload = objectMapper.readTree(outbox.get(0).getPayload());
        assertThat(payload.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(payload.path("eventId").asText()).isEqualTo(outbox.get(0).getEventId());
    }

    @Test
    void declinedPaymentIsRecordedAsFailed() throws Exception {
        when(gateway.charge(any())).thenReturn(ChargeResult.declined("CARD_DECLINED"));

        PaymentProcessingResult result = paymentService.process(request("ORD-20260101-0000000B", "tok_declined"));

        assertThat(result.isCreated()).isTrue();
        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.getPayment().getFailureReason()).isEqualTo("CARD_DECLINED");
        List<OutboxEvent> outbox = outboxRepository.findAll();
        assertThat(outbox).hasSize(1);
        JsonNode payload = objectMapper.readTree(outbox.get(0).getPayload());
        assertThat(payload.path("status").asText()).isEqualTo("FAILED");
        assertThat(payload.path("failureReason").asText()).isEqualTo("CARD_DECLINED");
    }

    @Test
    void replayWithSameIdempotencyKeyReturnsExistingPaymentWithoutCharging() {
        when(gateway.charge(any())).thenReturn(ChargeResult.approved("SIM-1"));
        PaymentProcessingResult first = paymentService.process(request("ORD-20260101-0000000C", "tok_visa"));

        PaymentProcessingResult second = paymentService.process(request("ORD-20260101-0000000C", "tok_visa"));

        assertThat(second.isCreated()).isFalse();
        assertThat(second.getPayment().getPaymentReference()).isEqualTo(first.getPayment().getPaymentReference());
        assertThat(second.getPayment().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(gateway, times(1)).charge(any());
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    void lateSuccessAfterOrderCancellationIsRefundedImmediately() {
        final String orderNumber = "ORD-20260101-0000000D";
        // While the (slow) gateway call is running, order-service gives up and publishes order-cancelled.
        when(gateway.charge(any())).thenAnswer(invocation -> {
            cancellationHandler.handle(OrderCancelledEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .orderNumber(orderNumber)
                    .customerId("CUST-1001")
                    .previousStatus("INVENTORY_RESERVED")
                    .reason(CancellationReason.PAYMENT_ERROR)
                    .build(), KafkaTopics.ORDER_CANCELLED);
            return ChargeResult.approved("SIM-LATE");
        });

        PaymentProcessingResult result = paymentService.process(request(orderNumber, "tok_timeout"));

        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        Payment stored = paymentRepository.findByPaymentReference(result.getPayment().getPaymentReference()).get();
        assertThat(stored.isCancelRequested()).isTrue();
        List<String> topics = outboxRepository.findAll().stream().map(OutboxEvent::getTopic)
                .collect(Collectors.toList());
        assertThat(topics).containsExactly(KafkaTopics.PAYMENT_PROCESSED, KafkaTopics.PAYMENT_REFUNDED);
    }

    @Test
    void gatewayErrorStoresFailedPaymentAndThrows503() throws Exception {
        when(gateway.charge(any())).thenThrow(new PaymentGatewayException("boom"));

        assertThatThrownBy(() -> paymentService.process(request("ORD-20260101-0000000E", "tok_error")))
                .isInstanceOf(ServiceUnavailableException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("PAYMENT_GATEWAY_ERROR");
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(be.getDetails()).containsKey("paymentReference");
                });

        Payment stored = paymentRepository.findByIdempotencyKey("PAY-ORD-20260101-0000000E").get();
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stored.getFailureReason()).isEqualTo(PaymentStateService.REASON_GATEWAY_ERROR);
        List<OutboxEvent> outbox = outboxRepository.findAll();
        assertThat(outbox).hasSize(1);
        assertThat(objectMapper.readTree(outbox.get(0).getPayload()).path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void manualRefundOfSucceededPaymentPublishesRefund() {
        when(gateway.charge(any())).thenReturn(ChargeResult.approved("SIM-1"));
        String reference = paymentService.process(request("ORD-20260101-0000000F", "tok_visa"))
                .getPayment().getPaymentReference();

        assertThat(paymentService.refund(reference).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(outboxRepository.findAll()).extracting(OutboxEvent::getTopic)
                .containsExactly(KafkaTopics.PAYMENT_PROCESSED, KafkaTopics.PAYMENT_REFUNDED);

        // Refunding twice is a state conflict, not a second refund.
        assertThatThrownBy(() -> paymentService.refund(reference))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_PAYMENT_STATE");
    }

    @Test
    void paymentForOrderCancelledBeforehandIsRefusedWithoutCallingTheGateway() throws Exception {
        final String orderNumber = "ORD-20260101-00000010";
        // order-cancelled is consumed first: no payment exists yet, so a tombstone is stored.
        cancellationHandler.handle(cancelled(orderNumber), KafkaTopics.ORDER_CANCELLED);
        assertThat(cancelledOrderRepository.existsById(orderNumber)).isTrue();

        // ...then the delayed payment request arrives.
        PaymentProcessingResult result = paymentService.process(request(orderNumber, "tok_visa"));

        assertThat(result.isCreated()).isTrue();
        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.getPayment().getFailureReason())
                .isEqualTo(PaymentStateService.REASON_ORDER_ALREADY_CANCELLED);
        verify(gateway, never()).charge(any());
        List<OutboxEvent> outbox = outboxRepository.findAll();
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).getTopic()).isEqualTo(KafkaTopics.PAYMENT_PROCESSED);
        JsonNode payload = objectMapper.readTree(outbox.get(0).getPayload());
        assertThat(payload.path("status").asText()).isEqualTo("FAILED");
        assertThat(payload.path("failureReason").asText()).isEqualTo("ORDER_ALREADY_CANCELLED");

        // A retry with the same key replays the refused attempt (idempotent), still without charging.
        PaymentProcessingResult replay = paymentService.process(request(orderNumber, "tok_visa"));
        assertThat(replay.isCreated()).isFalse();
        assertThat(replay.getPayment().getPaymentReference()).isEqualTo(result.getPayment().getPaymentReference());
        verify(gateway, never()).charge(any());
    }

    @Test
    void tombstoneWrittenDuringTheChargeIsSeenByTheCompletionStep() {
        final String orderNumber = "ORD-20260101-00000011";
        // Race: the consumer looked for payments BEFORE step 1 committed (found none -> tombstone), and step 1 checked
        // the tombstone BEFORE the consumer committed. Simulated by writing the tombstone during the gateway call.
        when(gateway.charge(any())).thenAnswer(invocation -> {
            cancelledOrderRepository.save(new CancelledOrder(orderNumber, "PAYMENT_ERROR", Instant.now()));
            return ChargeResult.approved("SIM-RACE");
        });

        PaymentProcessingResult result = paymentService.process(request(orderNumber, "tok_visa"));

        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        Payment stored = paymentRepository.findByPaymentReference(result.getPayment().getPaymentReference()).get();
        assertThat(stored.isCancelRequested()).isTrue();
        assertThat(outboxRepository.findAll()).extracting(OutboxEvent::getTopic)
                .containsExactly(KafkaTopics.PAYMENT_PROCESSED, KafkaTopics.PAYMENT_REFUNDED);
    }
}
