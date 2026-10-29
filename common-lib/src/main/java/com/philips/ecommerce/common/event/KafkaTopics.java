package com.philips.ecommerce.common.event;

/**
 * Single source of truth for Kafka topic names used by every producer and consumer.
 * The same names are created by docker/kafka/create-topics.sh and documented in docs/CONTRACTS.md.
 * Dead-letter topics use Spring Kafka's default suffix: "&lt;topic&gt;.DLT".
 */
public final class KafkaTopics {

    public static final String ORDER_CREATED = "order-created";
    public static final String ORDER_CONFIRMED = "order-confirmed";
    public static final String ORDER_CANCELLED = "order-cancelled";
    public static final String PAYMENT_PROCESSED = "payment-processed";
    public static final String PAYMENT_REFUNDED = "payment-refunded";

    public static final String DLT_SUFFIX = ".DLT";

    /** Header carrying the event type, e.g. "OrderCreatedEvent" (useful for tooling and DLT replay). */
    public static final String HEADER_EVENT_TYPE = "eventType";
    /** Header carrying the request correlation id across HTTP and Kafka hops. */
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

    private KafkaTopics() {
    }
}
