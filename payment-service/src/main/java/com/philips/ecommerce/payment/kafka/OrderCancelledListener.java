package com.philips.ecommerce.payment.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.web.CorrelationId;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * Kafka adapter: parses the JSON record and delegates to the transactional {@link OrderCancellationHandler}.
 * <p>
 * Offsets are committed per record AFTER this method returns (ack-mode: record, auto-commit disabled), i.e. only
 * once the DB transaction committed. Exceptions go to the DefaultErrorHandler (retry, then order-cancelled.DLT).
 * Malformed JSON ({@link JsonProcessingException}) and invalid events ({@link IllegalArgumentException}) are not
 * retried - they could never succeed.
 */
@Component
public class OrderCancelledListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelledListener.class);

    private final ObjectMapper objectMapper;
    private final OrderCancellationHandler handler;

    public OrderCancelledListener(ObjectMapper objectMapper, OrderCancellationHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @KafkaListener(topics = KafkaTopics.ORDER_CANCELLED, groupId = "${spring.application.name}")
    public void onOrderCancelled(ConsumerRecord<String, String> record) throws JsonProcessingException {
        String correlationId = header(record, KafkaTopics.HEADER_CORRELATION_ID);
        if (correlationId != null) {
            MDC.put(CorrelationId.MDC_KEY, correlationId);
        }
        try {
            OrderCancelledEvent event = objectMapper.readValue(record.value(), OrderCancelledEvent.class);
            validate(event);
            log.info("Received {} {} for order {} (partition {}, offset {})", OrderCancelledEvent.TYPE,
                    event.getEventId(), event.getOrderNumber(), record.partition(), record.offset());
            handler.handle(event, record.topic());
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private static void validate(OrderCancelledEvent event) {
        if (event == null || !StringUtils.hasText(event.getEventId()) || !StringUtils.hasText(event.getOrderNumber())) {
            throw new IllegalArgumentException("order-cancelled event without eventId/orderNumber");
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
