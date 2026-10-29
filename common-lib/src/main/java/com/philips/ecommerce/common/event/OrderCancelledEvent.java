package com.philips.ecommerce.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Topic: {@link KafkaTopics#ORDER_CANCELLED}. Key: orderNumber. Producer: order-service (via outbox).
 * This is the saga's COMPENSATION trigger:
 * inventory-service releases (or restocks) the reservation, payment-service voids/refunds any captured payment,
 * notification-service informs the customer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelledEvent {
    public static final String TYPE = "OrderCancelledEvent";

    private String eventId;
    private Instant occurredAt;
    private String orderNumber;
    private String customerId;
    /** Order status before cancellation, e.g. INVENTORY_RESERVED or CONFIRMED. */
    private String previousStatus;
    private CancellationReason reason;
    private String reasonDetail;
    private List<OrderItemPayload> items;
}
