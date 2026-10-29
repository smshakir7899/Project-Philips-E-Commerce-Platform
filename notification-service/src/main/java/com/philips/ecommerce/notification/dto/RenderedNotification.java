package com.philips.ecommerce.notification.dto;

import com.philips.ecommerce.notification.entity.NotificationType;
import lombok.Value;

/** Output of the template service: what to tell the customer, independent of the delivery channel. */
@Value
public class RenderedNotification {
    NotificationType type;
    String subject;
    String message;
}
