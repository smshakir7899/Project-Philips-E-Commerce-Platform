package com.philips.ecommerce.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Topic: {@link KafkaTopics#ORDER_CONFIRMED}. Key: orderNumber. Producer: order-service (via outbox).
 * Consumers: inventory-service (commit reservation), cart-service (clear cart), notification-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderConfirmedEvent {
    public static final String TYPE = "OrderConfirmedEvent";

    private String eventId;
    private Instant occurredAt;
    private String orderNumber;
    private String customerId;
    private BigDecimal totalAmount;
    private String currency;
    private String paymentReference;
    private List<OrderItemPayload> items;
}
