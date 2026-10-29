package com.philips.ecommerce.notification.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.common.event.OrderCreatedEvent;
import com.philips.ecommerce.common.event.PaymentProcessedEvent;
import com.philips.ecommerce.common.event.PaymentRefundedEvent;
import com.philips.ecommerce.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Consumes the five business topics (group {@code notification-service} = spring.application.name).
 *
 * <p>Values are JSON strings (StringDeserializer) mapped here with Spring's ObjectMapper rather than by a
 * JsonDeserializer: a malformed payload then surfaces as a {@link JsonProcessingException} inside the listener,
 * which the error handler classifies as non-retryable and dead-letters with the raw text intact. Boot's ObjectMapper
 * ignores unknown properties, so producers can add fields without breaking this consumer (schema evolution).</p>
 *
 * <p>Exceptions are not caught here on purpose: they must reach the container's error handler (retry -> DLT).
 * The offset is committed only after the method returns normally (ack-mode RECORD, auto-commit disabled).</p>
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    public NotificationEventListener(ObjectMapper objectMapper, NotificationService notificationService) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @KafkaListener(id = "notification-order-created", topics = KafkaTopics.ORDER_CREATED,
            groupId = "${spring.application.name}")
    public void onOrderCreated(ConsumerRecord<String, String> record) throws JsonProcessingException {
        process(record, OrderCreatedEvent.class, notificationService::handle);
    }

    @KafkaListener(id = "notification-order-confirmed", topics = KafkaTopics.ORDER_CONFIRMED,
            groupId = "${spring.application.name}")
    public void onOrderConfirmed(ConsumerRecord<String, String> record) throws JsonProcessingException {
        process(record, OrderConfirmedEvent.class, notificationService::handle);
    }

    @KafkaListener(id = "notification-order-cancelled", topics = KafkaTopics.ORDER_CANCELLED,
            groupId = "${spring.application.name}")
    public void onOrderCancelled(ConsumerRecord<String, String> record) throws JsonProcessingException {
        process(record, OrderCancelledEvent.class, notificationService::handle);
    }

    @KafkaListener(id = "notification-payment-processed", topics = KafkaTopics.PAYMENT_PROCESSED,
            groupId = "${spring.application.name}")
    public void onPaymentProcessed(ConsumerRecord<String, String> record) throws JsonProcessingException {
        process(record, PaymentProcessedEvent.class, notificationService::handle);
    }

    @KafkaListener(id = "notification-payment-refunded", topics = KafkaTopics.PAYMENT_REFUNDED,
            groupId = "${spring.application.name}")
    public void onPaymentRefunded(ConsumerRecord<String, String> record) throws JsonProcessingException {
        process(record, PaymentRefundedEvent.class, notificationService::handle);
    }

    <T> void process(ConsumerRecord<String, String> record, Class<T> eventClass, Function<T, Boolean> handler)
            throws JsonProcessingException {
        try (KafkaMdc ignored = KafkaMdc.forRecord(record)) {
            if (record.value() == null) {
                throw new IllegalArgumentException("Record " + record.topic() + "-" + record.partition() + "@"
                        + record.offset() + " has no value");
            }
            boolean replayed = record.headers().lastHeader(EventTopics.HEADER_REPLAYED_FROM_DLT) != null;
            log.debug("Received {} from {}-{}@{} key={}{}", eventClass.getSimpleName(), record.topic(),
                    record.partition(), record.offset(), record.key(), replayed ? " (replayed from DLT)" : "");

            T event = objectMapper.readValue(record.value(), eventClass);
            handler.apply(event);
        }
    }
}
