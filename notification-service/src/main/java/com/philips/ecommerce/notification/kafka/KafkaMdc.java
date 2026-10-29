package com.philips.ecommerce.notification.kafka;

import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.web.CorrelationId;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

/**
 * Puts the record's {@code X-Correlation-Id} header into the SLF4J MDC for the duration of a listener invocation,
 * so every log line written while handling the event carries the id of the HTTP request that caused it
 * (checkout -> outbox -> Kafka -> here). Use with try-with-resources; the previous MDC value is restored because
 * listener threads are pooled and reused.
 */
public final class KafkaMdc implements AutoCloseable {

    private final String previous;

    private KafkaMdc(String correlationId) {
        this.previous = MDC.get(CorrelationId.MDC_KEY);
        MDC.put(CorrelationId.MDC_KEY, correlationId);
    }

    public static KafkaMdc forRecord(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(KafkaTopics.HEADER_CORRELATION_ID);
        String correlationId = header != null && header.value() != null
                ? new String(header.value(), StandardCharsets.UTF_8)
                // No header (e.g. produced by a tool): derive a stable, searchable id from the record position
                : record.topic() + "-" + record.partition() + "-" + record.offset();
        return new KafkaMdc(correlationId);
    }

    @Override
    public void close() {
        if (previous != null) {
            MDC.put(CorrelationId.MDC_KEY, previous);
        } else {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
