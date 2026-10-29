package com.philips.ecommerce.notification.kafka;

import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.common.event.OrderCreatedEvent;
import com.philips.ecommerce.common.event.PaymentProcessedEvent;
import com.philips.ecommerce.common.event.PaymentRefundedEvent;

/**
 * Topic names used by this service. The DLT names are compile-time constants so they can be used inside
 * {@code @KafkaListener(topics = ...)} annotations.
 */
public final class EventTopics {

    public static final String ORDER_CREATED_DLT = KafkaTopics.ORDER_CREATED + KafkaTopics.DLT_SUFFIX;
    public static final String ORDER_CONFIRMED_DLT = KafkaTopics.ORDER_CONFIRMED + KafkaTopics.DLT_SUFFIX;
    public static final String ORDER_CANCELLED_DLT = KafkaTopics.ORDER_CANCELLED + KafkaTopics.DLT_SUFFIX;
    public static final String PAYMENT_PROCESSED_DLT = KafkaTopics.PAYMENT_PROCESSED + KafkaTopics.DLT_SUFFIX;
    public static final String PAYMENT_REFUNDED_DLT = KafkaTopics.PAYMENT_REFUNDED + KafkaTopics.DLT_SUFFIX;

    /** Header added when an operator replays a dead-lettered record (consumers may log it; they do not need it). */
    public static final String HEADER_REPLAYED_FROM_DLT = "x-replayed-from-dlt";

    private EventTopics() {
    }

    public static String[] deadLetterTopics() {
        return new String[]{ORDER_CREATED_DLT, ORDER_CONFIRMED_DLT, ORDER_CANCELLED_DLT, PAYMENT_PROCESSED_DLT,
                PAYMENT_REFUNDED_DLT};
    }

    public static String stripDltSuffix(String topic) {
        if (topic != null && topic.endsWith(KafkaTopics.DLT_SUFFIX)) {
            return topic.substring(0, topic.length() - KafkaTopics.DLT_SUFFIX.length());
        }
        return topic;
    }

    /** Value of the {@code eventType} header that producers put on each main topic, or null for unknown topics. */
    public static String eventTypeFor(String topic) {
        if (KafkaTopics.ORDER_CREATED.equals(topic)) {
            return OrderCreatedEvent.TYPE;
        }
        if (KafkaTopics.ORDER_CONFIRMED.equals(topic)) {
            return OrderConfirmedEvent.TYPE;
        }
        if (KafkaTopics.ORDER_CANCELLED.equals(topic)) {
            return OrderCancelledEvent.TYPE;
        }
        if (KafkaTopics.PAYMENT_PROCESSED.equals(topic)) {
            return PaymentProcessedEvent.TYPE;
        }
        if (KafkaTopics.PAYMENT_REFUNDED.equals(topic)) {
            return PaymentRefundedEvent.TYPE;
        }
        return null;
    }
}
