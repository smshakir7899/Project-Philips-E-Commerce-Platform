package com.philips.ecommerce.cart.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.event.KafkaTopics;
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
 * Consumes order-confirmed (group id = spring.application.name). The value is a
 * JSON string mapped with Spring's ObjectMapper, so a malformed payload fails
 * inside the listener (JsonProcessingException, not retried -> DLT) instead of
 * looping in the deserializer. Transactional work is delegated to
 * {@link CartEventHandler}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderConfirmedListener {

	static final String MDC_EVENT_ID = "eventId";
	static final String MDC_ORDER_NUMBER = "orderNumber";

	private final ObjectMapper objectMapper;
	private final CartEventHandler handler;

	@KafkaListener(topics = KafkaTopics.ORDER_CONFIRMED, groupId = "${spring.application.name}", autoStartup = "${app.kafka.listener.auto-startup:true}")
	public void onOrderConfirmed(ConsumerRecord<String, String> record) throws JsonProcessingException {
		if (record.value() == null) {
			throw new IllegalArgumentException("Empty payload on " + record.topic() + " offset " + record.offset());
		}
		OrderConfirmedEvent event = objectMapper.readValue(record.value(), OrderConfirmedEvent.class);
		requireText(event.getEventId(), "eventId");
		requireText(event.getOrderNumber(), "orderNumber");
		requireText(event.getCustomerId(), "customerId");

		Header correlation = record.headers().lastHeader(KafkaTopics.HEADER_CORRELATION_ID);
		if (correlation != null && correlation.value() != null) {
			MDC.put(CorrelationId.MDC_KEY, new String(correlation.value(), StandardCharsets.UTF_8));
		}
		MDC.put(MDC_EVENT_ID, event.getEventId());
		MDC.put(MDC_ORDER_NUMBER, event.getOrderNumber());
		try {
			log.info("Received {} eventId={} orderNumber={} customerId={} (partition {}, offset {})",
					OrderConfirmedEvent.TYPE, event.getEventId(), event.getOrderNumber(), event.getCustomerId(),
					record.partition(), record.offset());
			handler.onOrderConfirmed(event, record.topic());
		} finally {
			// listener threads are reused: never leak MDC values into the next record
			MDC.remove(CorrelationId.MDC_KEY);
			MDC.remove(MDC_EVENT_ID);
			MDC.remove(MDC_ORDER_NUMBER);
		}
	}

	/**
	 * IllegalArgumentException is configured as not retryable: an incomplete event
	 * goes straight to the DLT.
	 */
	private static void requireText(String value, String field) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException("Event field '" + field + "' is missing");
		}
	}
}
