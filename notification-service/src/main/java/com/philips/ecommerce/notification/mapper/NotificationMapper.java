package com.philips.ecommerce.notification.mapper;

import com.philips.ecommerce.notification.dto.FailedEventResponse;
import com.philips.ecommerce.notification.dto.NotificationResponse;
import com.philips.ecommerce.notification.entity.FailedEvent;
import com.philips.ecommerce.notification.entity.Notification;
import org.springframework.stereotype.Component;

/** Entity -> DTO mapping. Entities never leave the service layer (no lazy-loading or schema leaks in the API). */
@Component
public class NotificationMapper {

    public NotificationResponse toResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .eventId(notification.getEventId())
                .customerId(notification.getCustomerId())
                .orderNumber(notification.getOrderNumber())
                .type(notification.getType())
                .channel(notification.getChannel())
                .recipient(notification.getRecipient())
                .subject(notification.getSubject())
                .message(notification.getMessage())
                .status(notification.getStatus())
                .createdAt(notification.getCreatedAt())
                .build();
    }

    public FailedEventResponse toResponse(FailedEvent failedEvent) {
        return FailedEventResponse.builder()
                .id(failedEvent.getId())
                .originalTopic(failedEvent.getOriginalTopic())
                .messageKey(failedEvent.getMessageKey())
                .payload(failedEvent.getPayload())
                .exceptionMessage(failedEvent.getExceptionMessage())
                .consumerGroup(failedEvent.getConsumerGroup())
                .status(failedEvent.getStatus())
                .createdAt(failedEvent.getCreatedAt())
                .replayedAt(failedEvent.getReplayedAt())
                .build();
    }
}
