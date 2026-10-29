package com.philips.ecommerce.notification.client;

import com.philips.ecommerce.notification.client.dto.EmailMessage;
import com.philips.ecommerce.notification.exception.NotificationDeliveryException;

/**
 * Port to an e-mail provider (SMTP, Amazon SES, SendGrid...). The rest of the service depends only on this
 * interface, so swapping the simulated implementation for a real one is a single new bean.
 */
public interface EmailSender {

    /**
     * Hands the message to the provider. Must be synchronous: returning normally means "accepted", which is what
     * allows the caller to mark the notification SENT and the Kafka offset to be committed afterwards.
     *
     * @throws NotificationDeliveryException if the provider did not accept the message (retryable)
     */
    void send(EmailMessage message);
}
