package com.philips.ecommerce.notification.kafka;

import com.philips.ecommerce.notification.dto.DeadLetterRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Decodes the headers that Spring Kafka's {@code DeadLetterPublishingRecoverer} (2.8) adds to a dead-lettered record.
 *
 * <p>Encoding matters: topic, consumer group, exception class and message are UTF-8 strings, but the original
 * partition is a 4-byte big-endian int and the original offset an 8-byte big-endian long
 * ({@code ByteBuffer.putInt/putLong}). Reading those as strings would produce garbage. For robustness (a record put
 * on the DLT by hand or by another tool) string-encoded numbers are accepted too.</p>
 *
 * <p>{@code lastHeader} is used everywhere: if a record is dead-lettered more than once the most recent failure wins.</p>
 */
public final class DltHeaders {

    /** Column size of failed_event.exception_message. */
    static final int MAX_EXCEPTION_MESSAGE_LENGTH = 2000;

    private DltHeaders() {
    }

    public static DeadLetterRecord toDeadLetterRecord(ConsumerRecord<String, String> record) {
        Headers headers = record.headers();
        String originalTopic = string(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (originalTopic == null) {
            originalTopic = EventTopics.stripDltSuffix(record.topic());
        }
        // DLT_EXCEPTION_FQCN is usually the container's ListenerExecutionFailedException wrapper;
        // the CAUSE class is the interesting one (e.g. NotificationDeliveryException, InsufficientStockException).
        String exceptionClass = string(headers, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        if (exceptionClass == null) {
            exceptionClass = string(headers, KafkaHeaders.DLT_EXCEPTION_FQCN);
        }
        return DeadLetterRecord.builder()
                .originalTopic(originalTopic)
                .originalPartition(integer(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION))
                .originalOffset(longValue(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET))
                .messageKey(record.key())
                .payload(record.value())
                .exceptionClass(exceptionClass)
                .exceptionMessage(truncate(string(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE)))
                .consumerGroup(string(headers, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP))
                .build();
    }

    static String string(Headers headers, String name) {
        byte[] value = raw(headers, name);
        return value != null ? new String(value, StandardCharsets.UTF_8) : null;
    }

    static Integer integer(Headers headers, String name) {
        byte[] value = raw(headers, name);
        if (value == null) {
            return null;
        }
        if (value.length == Integer.BYTES) {
            return ByteBuffer.wrap(value).getInt();
        }
        Long parsed = parseOrNull(value);
        return parsed != null ? Integer.valueOf(parsed.intValue()) : null;
    }

    static Long longValue(Headers headers, String name) {
        byte[] value = raw(headers, name);
        if (value == null) {
            return null;
        }
        if (value.length == Long.BYTES) {
            return ByteBuffer.wrap(value).getLong();
        }
        if (value.length == Integer.BYTES) {
            return (long) ByteBuffer.wrap(value).getInt();
        }
        return parseOrNull(value);
    }

    private static byte[] raw(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return header != null ? header.value() : null;
    }

    private static Long parseOrNull(byte[] value) {
        try {
            return Long.valueOf(new String(value, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_EXCEPTION_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_EXCEPTION_MESSAGE_LENGTH);
    }
}
