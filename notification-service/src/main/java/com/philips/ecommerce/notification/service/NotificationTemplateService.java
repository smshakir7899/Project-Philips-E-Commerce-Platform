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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Turns domain events into customer-facing text. Pure function of the event (no I/O), which keeps it trivially
 * unit-testable; a real system would use a template engine (Thymeleaf/FreeMarker) and localisation here.
 */
@Service
public class NotificationTemplateService {

    private static final String SIGNATURE = "\n\nThank you for shopping with Philips.\nPhilips Online Shop";

    public RenderedNotification orderPlaced(OrderCreatedEvent event) {
        StringBuilder body = new StringBuilder()
                .append("Hello ").append(event.getCustomerId()).append(",\n\n")
                .append("we have received your order ").append(event.getOrderNumber())
                .append(" and are now reserving the items and processing your payment.\n\n");
        appendItems(body, event.getItems(), event.getCurrency());
        body.append("Total: ").append(money(event.getTotalAmount(), event.getCurrency()))
                .append("\n\nYou will receive another message as soon as the order is confirmed.")
                .append(SIGNATURE);
        return new RenderedNotification(NotificationType.ORDER_PLACED,
                "We received your order " + event.getOrderNumber(), body.toString());
    }

    public RenderedNotification orderConfirmed(OrderConfirmedEvent event) {
        StringBuilder body = new StringBuilder()
                .append("Hello ").append(event.getCustomerId()).append(",\n\n")
                .append("good news: your order ").append(event.getOrderNumber())
                .append(" is confirmed and will be shipped soon.\n\n");
        appendItems(body, event.getItems(), event.getCurrency());
        body.append("Total paid: ").append(money(event.getTotalAmount(), event.getCurrency())).append('\n');
        if (event.getPaymentReference() != null) {
            body.append("Payment reference: ").append(event.getPaymentReference()).append('\n');
        }
        body.append(SIGNATURE);
        return new RenderedNotification(NotificationType.ORDER_CONFIRMED,
                "Your order " + event.getOrderNumber() + " is confirmed", body.toString());
    }

    public RenderedNotification orderCancelled(OrderCancelledEvent event) {
        StringBuilder body = new StringBuilder()
                .append("Hello ").append(event.getCustomerId()).append(",\n\n")
                .append("your order ").append(event.getOrderNumber()).append(" has been cancelled. ")
                .append(cancellationExplanation(event.getReason()));
        if (event.getReasonDetail() != null && !event.getReasonDetail().trim().isEmpty()) {
            body.append("\n\nDetails: ").append(event.getReasonDetail());
        }
        body.append(SIGNATURE);
        return new RenderedNotification(NotificationType.ORDER_CANCELLED,
                "Your order " + event.getOrderNumber() + " has been cancelled", body.toString());
    }

    public RenderedNotification paymentProcessed(PaymentProcessedEvent event) {
        String amount = money(event.getAmount(), event.getCurrency());
        if (event.getStatus() == PaymentResultStatus.SUCCEEDED) {
            String body = "Hello " + event.getCustomerId() + ",\n\n"
                    + "we have received your payment of " + amount + " for order " + event.getOrderNumber() + ".\n"
                    + "Payment reference: " + event.getPaymentReference() + SIGNATURE;
            return new RenderedNotification(NotificationType.PAYMENT_SUCCEEDED,
                    "Payment received for order " + event.getOrderNumber(), body);
        }
        String reason = event.getFailureReason() != null ? event.getFailureReason() : "UNKNOWN";
        String body = "Hello " + event.getCustomerId() + ",\n\n"
                + "unfortunately your payment of " + amount + " for order " + event.getOrderNumber()
                + " could not be completed (reason: " + reason + ").\n"
                + "You have not been charged. Please try again with another payment method." + SIGNATURE;
        return new RenderedNotification(NotificationType.PAYMENT_FAILED,
                "Payment failed for order " + event.getOrderNumber(), body);
    }

    public RenderedNotification paymentRefunded(PaymentRefundedEvent event) {
        StringBuilder body = new StringBuilder()
                .append("Hello ").append(event.getCustomerId()).append(",\n\n")
                .append("we have refunded ").append(money(event.getAmount(), event.getCurrency()))
                .append(" for order ").append(event.getOrderNumber())
                .append(" (payment reference ").append(event.getPaymentReference()).append(").\n");
        if (event.getReason() != null) {
            body.append("Reason: ").append(event.getReason()).append('\n');
        }
        body.append("Depending on your bank it can take 3-5 business days to appear on your statement.")
                .append(SIGNATURE);
        return new RenderedNotification(NotificationType.PAYMENT_REFUNDED,
                "Refund issued for order " + event.getOrderNumber(), body.toString());
    }

    /** The text depends on WHY the saga cancelled the order - the customer needs to know whether they were charged. */
    String cancellationExplanation(CancellationReason reason) {
        if (reason == null) {
            return "If you were charged, the amount will be refunded automatically.";
        }
        switch (reason) {
            case INSUFFICIENT_STOCK:
                return "Unfortunately some items are no longer in stock. You have not been charged.";
            case INVENTORY_ERROR:
                return "We could not reserve your items due to a technical problem. You have not been charged. "
                        + "Please try again in a few minutes.";
            case PAYMENT_DECLINED:
                return "Your payment was declined by the card issuer. You have not been charged. "
                        + "Please try again with another card.";
            case PAYMENT_ERROR:
                return "We could not confirm your payment due to a technical problem. "
                        + "If any amount was charged it will be refunded automatically.";
            case CUSTOMER_REQUESTED:
                return "We have cancelled it as you requested. Your payment will be refunded.";
            default:
                return "If you were charged, the amount will be refunded automatically.";
        }
    }

    private void appendItems(StringBuilder body, List<OrderItemPayload> items, String currency) {
        if (items == null || items.isEmpty()) {
            return;
        }
        body.append("Items:\n");
        for (OrderItemPayload item : items) {
            body.append("  - ").append(item.getQuantity()).append(" x ").append(item.getProductName());
            if (item.getSku() != null) {
                body.append(" (").append(item.getSku()).append(')');
            }
            body.append(" @ ").append(money(item.getUnitPrice(), currency)).append('\n');
        }
        body.append('\n');
    }

    /** Money is BigDecimal with scale 2 everywhere in the platform; never format it through double. */
    private String money(BigDecimal amount, String currency) {
        String value = amount != null ? amount.setScale(2, RoundingMode.HALF_UP).toPlainString() : "?";
        return value + " " + (currency != null ? currency : "EUR");
    }
}
