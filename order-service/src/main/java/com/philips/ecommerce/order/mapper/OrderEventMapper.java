package com.philips.ecommerce.order.mapper;

import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.common.event.OrderCreatedEvent;
import com.philips.ecommerce.common.event.OrderItemPayload;
import com.philips.ecommerce.order.entity.Order;
import com.philips.ecommerce.order.entity.OrderItem;
import com.philips.ecommerce.order.entity.OrderStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the Kafka event payloads (classes from common-lib = the published contract). Each event gets a fresh UUID
 * eventId, which consumers store in processed_event to ignore redeliveries.
 */
@Component
public class OrderEventMapper {

    private final Clock clock;

    public OrderEventMapper(Clock clock) {
        this.clock = clock;
    }

    public OrderCreatedEvent toCreated(Order order) {
        return OrderCreatedEvent.builder()
                .eventId(newEventId())
                .occurredAt(now())
                .orderNumber(order.getOrderNumber())
                .customerId(order.getCustomerId())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .items(items(order))
                .build();
    }

    public OrderConfirmedEvent toConfirmed(Order order) {
        return OrderConfirmedEvent.builder()
                .eventId(newEventId())
                .occurredAt(now())
                .orderNumber(order.getOrderNumber())
                .customerId(order.getCustomerId())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .paymentReference(order.getPaymentReference())
                .items(items(order))
                .build();
    }

    public OrderCancelledEvent toCancelled(Order order, OrderStatus previousStatus, CancellationReason reason,
                                           String reasonDetail) {
        return OrderCancelledEvent.builder()
                .eventId(newEventId())
                .occurredAt(now())
                .orderNumber(order.getOrderNumber())
                .customerId(order.getCustomerId())
                .previousStatus(previousStatus.name())
                .reason(reason)
                .reasonDetail(reasonDetail)
                .items(items(order))
                .build();
    }

    private List<OrderItemPayload> items(Order order) {
        return order.getItems().stream().map(this::toPayload).collect(Collectors.toList());
    }

    private OrderItemPayload toPayload(OrderItem item) {
        return OrderItemPayload.builder()
                .productId(item.getProductId())
                .sku(item.getSku())
                .productName(item.getProductName())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .build();
    }

    private String newEventId() {
        return UUID.randomUUID().toString();
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
