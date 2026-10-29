package com.philips.ecommerce.payment.service;

import com.philips.ecommerce.payment.client.PaymentGateway;
import com.philips.ecommerce.payment.client.dto.ChargeResult;
import com.philips.ecommerce.payment.dto.PaymentProcessingResult;
import com.philips.ecommerce.payment.dto.PaymentRequest;
import com.philips.ecommerce.payment.dto.PaymentResponse;
import com.philips.ecommerce.payment.entity.Payment;
import com.philips.ecommerce.payment.entity.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Orchestration branches that are hard to reproduce with a real database (races). */
@ExtendWith(MockitoExtension.class)
class PaymentServiceUnitTest {

    @Mock
    private PaymentStateService stateService;
    @Mock
    private PaymentGateway gateway;
    @InjectMocks
    private PaymentService paymentService;

    private final PaymentRequest request = PaymentRequest.builder()
            .orderNumber("ORD-20260101-00000001").customerId("CUST-1001").amount(new BigDecimal("10.00"))
            .currency("EUR").cardToken("tok_visa").idempotencyKey("PAY-ORD-20260101-00000001").build();

    private static PaymentResponse response(PaymentStatus status) {
        return PaymentResponse.builder().paymentReference("PAY-AAAAAAAAAAAA").orderNumber("ORD-20260101-00000001")
                .amount(new BigDecimal("10.00")).currency("EUR").status(status).build();
    }

    @Test
    void lostInsertRaceReplaysTheWinner() {
        when(stateService.findByIdempotencyKey(request.getIdempotencyKey()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(response(PaymentStatus.PROCESSING)));
        when(stateService.createProcessing(request)).thenThrow(new DataIntegrityViolationException("duplicate"));

        PaymentProcessingResult result = paymentService.process(request);

        assertThat(result.isCreated()).isFalse();
        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(gateway, never()).charge(any());
    }

    @Test
    void integrityViolationWithoutWinnerIsRethrown() {
        when(stateService.findByIdempotencyKey(request.getIdempotencyKey())).thenReturn(Optional.empty());
        DataIntegrityViolationException failure = new DataIntegrityViolationException("other constraint");
        when(stateService.createProcessing(request)).thenThrow(failure);

        assertThatThrownBy(() -> paymentService.process(request)).isSameAs(failure);
    }

    @Test
    void completionIsRetriedAfterOptimisticLockConflict() {
        ChargeResult approved = ChargeResult.approved("SIM-1");
        when(stateService.findByIdempotencyKey(request.getIdempotencyKey())).thenReturn(Optional.empty());
        when(stateService.createProcessing(request)).thenReturn(response(PaymentStatus.PROCESSING));
        when(gateway.charge(any())).thenReturn(approved);
        when(stateService.complete(eq("PAY-AAAAAAAAAAAA"), eq(approved)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, 1L))
                .thenReturn(response(PaymentStatus.REFUNDED));

        PaymentProcessingResult result = paymentService.process(request);

        assertThat(result.isCreated()).isTrue();
        assertThat(result.getPayment().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(stateService, times(2)).complete("PAY-AAAAAAAAAAAA", approved);
    }

    @Test
    void persistentOptimisticLockConflictIsPropagated() {
        ChargeResult approved = ChargeResult.approved("SIM-1");
        when(stateService.findByIdempotencyKey(request.getIdempotencyKey())).thenReturn(Optional.empty());
        when(stateService.createProcessing(request)).thenReturn(response(PaymentStatus.PROCESSING));
        when(gateway.charge(any())).thenReturn(approved);
        when(stateService.complete("PAY-AAAAAAAAAAAA", approved))
                .thenThrow(new ObjectOptimisticLockingFailureException(Payment.class, 1L));

        assertThatThrownBy(() -> paymentService.process(request))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(stateService, times(PaymentService.OPTIMISTIC_LOCK_ATTEMPTS)).complete("PAY-AAAAAAAAAAAA", approved);
    }

    @Test
    void tombstoneIsCheckedBeforeInsertingProcessing() {
        PaymentResponse refused = response(PaymentStatus.FAILED);
        refused.setFailureReason(PaymentStateService.REASON_ORDER_ALREADY_CANCELLED);
        when(stateService.findByIdempotencyKey(request.getIdempotencyKey())).thenReturn(Optional.empty());
        when(stateService.isOrderCancelled(request.getOrderNumber())).thenReturn(true);
        when(stateService.createRejectedForCancelledOrder(request)).thenReturn(refused);

        PaymentProcessingResult result = paymentService.process(request);

        assertThat(result.isCreated()).isTrue();
        assertThat(result.getPayment().getFailureReason()).isEqualTo("ORDER_ALREADY_CANCELLED");
        verify(stateService, never()).createProcessing(any());
        verify(gateway, never()).charge(any());
    }

    @Test
    void noTombstoneProceedsToTheGateway() {
        ChargeResult approved = ChargeResult.approved("SIM-1");
        when(stateService.findByIdempotencyKey(request.getIdempotencyKey())).thenReturn(Optional.empty());
        when(stateService.isOrderCancelled(request.getOrderNumber())).thenReturn(false);
        when(stateService.createProcessing(request)).thenReturn(response(PaymentStatus.PROCESSING));
        when(gateway.charge(any())).thenReturn(approved);
        when(stateService.complete("PAY-AAAAAAAAAAAA", approved)).thenReturn(response(PaymentStatus.SUCCEEDED));

        assertThat(paymentService.process(request).getPayment().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(stateService, never()).createRejectedForCancelledOrder(any());
    }
}
