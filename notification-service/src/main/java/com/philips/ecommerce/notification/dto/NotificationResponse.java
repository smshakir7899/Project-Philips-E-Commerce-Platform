package com.philips.ecommerce.notification.dto;

import com.philips.ecommerce.notification.entity.NotificationChannel;
import com.philips.ecommerce.notification.entity.NotificationStatus;
import com.philips.ecommerce.notification.entity.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {
    private Long id;
    private String eventId;
    private String customerId;
    private String orderNumber;
    private NotificationType type;
    private NotificationChannel channel;
    private String recipient;
    private String subject;
    private String message;
    private NotificationStatus status;
    private Instant createdAt;
}
