package com.philips.ecommerce.notification.exception;

/**
 * The (simulated) e-mail provider rejected or failed to accept a message.
 *
 * <p>Deliberately NOT a {@code BusinessException}: it never reaches an HTTP client. It is thrown inside a Kafka
 * listener, where it is treated as TRANSIENT - the container's DefaultErrorHandler retries the record with a fixed
 * back-off and, when retries are exhausted, the DeadLetterPublishingRecoverer sends it to {@code <topic>.DLT}.</p>
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
