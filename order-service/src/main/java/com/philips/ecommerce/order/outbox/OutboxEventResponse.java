package com.philips.ecommerce.order.outbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Admin view of an outbox row (payload included so operators can see what is stuck). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventResponse {
    private Long id;
    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String topic;
    private String messageKey;
    private String payload;
    private OutboxStatus status;
    private int attempts;
    private String lastError;
    private Instant createdAt;
    private Instant sentAt;

    public static OutboxEventResponse from(OutboxEvent e) {
        return OutboxEventResponse.builder()
                .id(e.getId())
                .eventId(e.getEventId())
                .aggregateType(e.getAggregateType())
                .aggregateId(e.getAggregateId())
                .eventType(e.getEventType())
                .topic(e.getTopic())
                .messageKey(e.getMessageKey())
                .payload(e.getPayload())
                .status(e.getStatus())
                .attempts(e.getAttempts())
                .lastError(e.getLastError())
                .createdAt(e.getCreatedAt())
                .sentAt(e.getSentAt())
                .build();
    }
}
