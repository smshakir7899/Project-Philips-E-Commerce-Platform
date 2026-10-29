package com.philips.ecommerce.notification.service;

import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.common.event.OrderCreatedEvent;
import com.philips.ecommerce.common.event.OrderItemPayload;
import com.philips.ecommerce.common.event.PaymentProcessedEvent;
import com.philips.ecommerce.common.event.PaymentRefundedEvent;
import com.philips.ecommerce.common.event.PaymentResultStatus;
import com.philips.ecommerce.notification.dto.RenderedNotification;
import com.philips.ecommerce.notification.entity.NotificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTemplateServiceTest {

    private final NotificationTemplateService templates = new NotificationTemplateService();

    @Test
    void orderPlacedListsItemsAndTotalWithTwoDecimals() {
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .eventId("e1").orderNumber("ORD-20240101-ABCDEF12").customerId("CUST-1001")
                .totalAmount(new BigDecimal("199.9")).currency("EUR")
                .items(Collections.singletonList(OrderItemPayload.builder()
                        .productId(1L).sku("HX9911").productName("Sonicare DiamondClean").quantity(2)
                        .unitPrice(new BigDecimal("99.95")).build()))
                .build();

        RenderedNotification rendered = templates.orderPlaced(event);

        assertThat(rendered.getType()).isEqualTo(NotificationType.ORDER_PLACED);
        assertThat(rendered.getSubject()).isEqualTo("We received your order ORD-20240101-ABCDEF12");
        assertThat(rendered.getMessage())
                .contains("2 x Sonicare DiamondClean (HX9911) @ 99.95 EUR")
                .contains("Total: 199.90 EUR");
    }

    @Test
    void orderConfirmedMentionsPaymentReference() {
        OrderConfirmedEvent event = OrderConfirmedEvent.builder()
                .eventId("e2").orderNumber("ORD-1").customerId("CUST-1").totalAmount(new BigDecimal("10.00"))
                .currency("EUR").paymentReference("PAY-123456789012").build();

        RenderedNotification rendered = templates.orderConfirmed(event);

        assertThat(rendered.getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(rendered.getSubject()).contains("is confirmed");
        assertThat(rendered.getMessage()).contains("PAY-123456789012").contains("Total paid: 10.00 EUR");
    }

    @Test
    void cancellationTextDependsOnReason() {
        assertThat(cancelled(CancellationReason.INSUFFICIENT_STOCK).getMessage())
                .contains("no longer in stock").contains("not been charged");
        assertThat(cancelled(CancellationReason.PAYMENT_DECLINED).getMessage())
                .contains("declined").contains("another card");
        assertThat(cancelled(CancellationReason.PAYMENT_ERROR).getMessage())
                .contains("refunded automatically");
        assertThat(cancelled(CancellationReason.CUSTOMER_REQUESTED).getMessage())
                .contains("as you requested");
        assertThat(cancelled(CancellationReason.INVENTORY_ERROR).getMessage())
                .contains("technical problem");
        assertThat(cancelled(null).getMessage()).contains("If you were charged");
    }

    @ParameterizedTest
    @EnumSource(CancellationReason.class)
    void everyCancellationReasonHasADedicatedText(CancellationReason reason) {
        assertThat(templates.cancellationExplanation(reason))
                .isNotEqualTo(templates.cancellationExplanation(null));
    }

    @Test
    void cancellationIncludesReasonDetailWhenPresent() {
        OrderCancelledEvent event = OrderCancelledEvent.builder()
                .eventId("e3").orderNumber("ORD-1").customerId("CUST-1")
                .reason(CancellationReason.INSUFFICIENT_STOCK).reasonDetail("product 7: requested 5, available 2")
                .build();

        RenderedNotification rendered = templates.orderCancelled(event);

        assertThat(rendered.getType()).isEqualTo(NotificationType.ORDER_CANCELLED);
        assertThat(rendered.getMessage()).contains("Details: product 7: requested 5, available 2");
    }

    @Test
    void paymentProcessedMapsToSucceededOrFailedType() {
        PaymentProcessedEvent succeeded = PaymentProcessedEvent.builder()
                .eventId("e4").orderNumber("ORD-1").customerId("CUST-1").paymentReference("PAY-1")
                .amount(new BigDecimal("42.5")).currency("EUR").status(PaymentResultStatus.SUCCEEDED).build();
        PaymentProcessedEvent failed = PaymentProcessedEvent.builder()
                .eventId("e5").orderNumber("ORD-1").customerId("CUST-1").paymentReference("PAY-2")
                .amount(new BigDecimal("42.50")).currency("EUR").status(PaymentResultStatus.FAILED)
                .failureReason("CARD_DECLINED").build();

        assertThat(templates.paymentProcessed(succeeded).getType()).isEqualTo(NotificationType.PAYMENT_SUCCEEDED);
        assertThat(templates.paymentProcessed(succeeded).getMessage()).contains("42.50 EUR").contains("PAY-1");
        assertThat(templates.paymentProcessed(failed).getType()).isEqualTo(NotificationType.PAYMENT_FAILED);
        assertThat(templates.paymentProcessed(failed).getMessage()).contains("CARD_DECLINED");
    }

    @Test
    void paymentRefunded() {
        PaymentRefundedEvent event = PaymentRefundedEvent.builder()
                .eventId("e6").orderNumber("ORD-1").customerId("CUST-1").paymentReference("PAY-9")
                .amount(new BigDecimal("15")).currency("EUR").reason("ORDER_CANCELLED").build();

        RenderedNotification rendered = templates.paymentRefunded(event);

        assertThat(rendered.getType()).isEqualTo(NotificationType.PAYMENT_REFUNDED);
        assertThat(rendered.getMessage()).contains("15.00 EUR").contains("PAY-9").contains("ORDER_CANCELLED");
    }

    private RenderedNotification cancelled(CancellationReason reason) {
        return templates.orderCancelled(OrderCancelledEvent.builder()
                .eventId("e").orderNumber("ORD-1").customerId("CUST-1").reason(reason).build());
    }
}
