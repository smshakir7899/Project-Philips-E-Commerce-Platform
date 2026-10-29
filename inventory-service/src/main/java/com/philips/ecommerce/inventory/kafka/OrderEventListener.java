package com.philips.ecommerce.inventory.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.common.web.CorrelationId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * Kafka entry point. Values are JSON strings (StringDeserializer) mapped here with Spring's ObjectMapper: a
 * malformed payload then fails with JsonProcessingException INSIDE the listener, where the error handler can send it
 * to the DLT (a failing deserializer would need an ErrorHandlingDeserializer to avoid a poison-pill loop).
 *
 * <p>The listener only parses, validates and sets up logging context; the transactional work is in
 * {@link OrderEventHandler} (a separate bean, so the @Transactional proxy is used).
 * Group id = spring.application.name, so all inventory-service instances share the partitions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    static final String MDC_EVENT_ID = "eventId";
    static final String MDC_ORDER_NUMBER = "orderNumber";

    private final ObjectMapper objectMapper;
    private final OrderEventHandler handler;

    @KafkaListener(topics = KafkaTopics.ORDER_CONFIRMED, groupId = "${spring.application.name}",
            autoStartup = "${app.kafka.listener.auto-startup:true}")
    public void onOrderConfirmed(ConsumerRecord<String, String> record) throws JsonProcessingException {
        OrderConfirmedEvent event = parse(record, OrderConfirmedEvent.class);
        requireText(event.getEventId(), "eventId");
        requireText(event.getOrderNumber(), "orderNumber");
        putMdc(record, event.getEventId(), event.getOrderNumber());
        try {
            log.info("Received {} eventId={} orderNumber={} (partition {}, offset {})", OrderConfirmedEvent.TYPE,
                    event.getEventId(), event.getOrderNumber(), record.partition(), record.offset());
            handler.onOrderConfirmed(event, record.topic());
        } finally {
            clearMdc();
        }
    }

    @KafkaListener(topics = KafkaTopics.ORDER_CANCELLED, groupId = "${spring.application.name}",
            autoStartup = "${app.kafka.listener.auto-startup:true}")
    public void onOrderCancelled(ConsumerRecord<String, String> record) throws JsonProcessingException {
        OrderCancelledEvent event = parse(record, OrderCancelledEvent.class);
        requireText(event.getEventId(), "eventId");
        requireText(event.getOrderNumber(), "orderNumber");
        putMdc(record, event.getEventId(), event.getOrderNumber());
        try {
            log.info("Received {} eventId={} orderNumber={} reason={} (partition {}, offset {})",
                    OrderCancelledEvent.TYPE, event.getEventId(), event.getOrderNumber(), event.getReason(),
                    record.partition(), record.offset());
            handler.onOrderCancelled(event, record.topic());
        } finally {
            clearMdc();
        }
    }

    private <T> T parse(ConsumerRecord<String, String> record, Class<T> type) throws JsonProcessingException {
        if (record.value() == null) {
            throw new IllegalArgumentException("Empty payload on " + record.topic() + " offset " + record.offset());
        }
        return objectMapper.readValue(record.value(), type);
    }

    /** Invalid events can never succeed: IllegalArgumentException is configured as not retryable (-> DLT). */
    private static void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Event field '" + field + "' is missing");
        }
    }

    /** Listener threads are pooled: whatever is put in the MDC must be removed again in finally. */
    private static void putMdc(ConsumerRecord<String, String> record, String eventId, String orderNumber) {
        Header header = record.headers().lastHeader(KafkaTopics.HEADER_CORRELATION_ID);
        if (header != null && header.value() != null) {
            MDC.put(CorrelationId.MDC_KEY, new String(header.value(), StandardCharsets.UTF_8));
        }
        MDC.put(MDC_EVENT_ID, eventId);
        MDC.put(MDC_ORDER_NUMBER, orderNumber);
    }

    private static void clearMdc() {
        MDC.remove(CorrelationId.MDC_KEY);
        MDC.remove(MDC_EVENT_ID);
        MDC.remove(MDC_ORDER_NUMBER);
    }
}
