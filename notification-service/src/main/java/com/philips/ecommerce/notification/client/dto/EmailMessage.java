package com.philips.ecommerce.notification.client.dto;

import lombok.Value;

/** A channel-specific message handed to an {@link com.philips.ecommerce.notification.client.EmailSender}. */
@Value
public class EmailMessage {
    String from;
    String to;
    String subject;
    String body;
    /** Not part of a real e-mail; carried so the simulated sender can decide to fail for selected customers. */
    String customerId;
}
