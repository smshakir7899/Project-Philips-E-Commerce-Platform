package com.philips.ecommerce.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Topic: {@link KafkaTopics#ORDER_CREATED}. Key: orderNumber. Producer: order-service (via outbox). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {
    public static final String TYPE = "OrderCreatedEvent";

    /** Unique id used by consumers for idempotent (exactly-once effect) processing. */
    private String eventId;
    private Instant occurredAt;
    private String orderNumber;
    private String customerId;
    private BigDecimal totalAmount;
    private String currency;
    private List<OrderItemPayload> items;
}
