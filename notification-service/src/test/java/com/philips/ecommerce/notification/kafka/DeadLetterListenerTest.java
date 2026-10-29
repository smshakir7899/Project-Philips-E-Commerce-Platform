package com.philips.ecommerce.notification.kafka;

import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.notification.dto.DeadLetterRecord;
import com.philips.ecommerce.notification.service.FailedEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeadLetterListenerTest {

    @Mock
    private FailedEventService failedEventService;

    @Test
    void decodesHeadersWrittenByDeadLetterPublishingRecoverer() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(EventTopics.ORDER_CONFIRMED_DLT, 2, 0L,
                "ORD-20240101-ABCDEF12", "{\"eventId\":\"e1\"}");
        Headers headers = record.headers();
        // Same encodings as DeadLetterPublishingRecoverer: strings UTF-8, partition 4-byte int, offset 8-byte long
        headers.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, utf8(KafkaTopics.ORDER_CONFIRMED));
        headers.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(2).array());
        headers.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(1234567890123L).array());
        headers.add(KafkaHeaders.DLT_EXCEPTION_FQCN,
                utf8("org.springframework.kafka.listener.ListenerExecutionFailedException"));
        headers.add(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, utf8("com.example.InsufficientStockException"));
        headers.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, utf8("Listener failed; nested exception is ..."));
        headers.add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, utf8("inventory-service"));

        new DeadLetterListener(failedEventService).onDeadLetter(record);

        ArgumentCaptor<DeadLetterRecord> captor = ArgumentCaptor.forClass(DeadLetterRecord.class);
        verify(failedEventService).record(captor.capture());
        DeadLetterRecord dl = captor.getValue();
        assertThat(dl.getOriginalTopic()).isEqualTo("order-confirmed");
        assertThat(dl.getOriginalPartition()).isEqualTo(2);
        assertThat(dl.getOriginalOffset()).isEqualTo(1234567890123L);
        assertThat(dl.getMessageKey()).isEqualTo("ORD-20240101-ABCDEF12");
        assertThat(dl.getPayload()).isEqualTo("{\"eventId\":\"e1\"}");
        assertThat(dl.getExceptionClass()).isEqualTo("com.example.InsufficientStockException");
        assertThat(dl.getExceptionMessage()).startsWith("Listener failed");
        assertThat(dl.getConsumerGroup()).isEqualTo("inventory-service");
    }

    @Test
    void toleratesMissingHeadersAndStringEncodedNumbers() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(EventTopics.PAYMENT_REFUNDED_DLT, 0, 5L,
                "ORD-1", "{}");
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_OFFSET, utf8("77"));
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_FQCN, utf8("java.lang.IllegalStateException"));

        DeadLetterRecord dl = DltHeaders.toDeadLetterRecord(record);

        assertThat(dl.getOriginalTopic()).as("derived from DLT name").isEqualTo("payment-refunded");
        assertThat(dl.getOriginalPartition()).isNull();
        assertThat(dl.getOriginalOffset()).isEqualTo(77L);
        assertThat(dl.getExceptionClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(dl.getConsumerGroup()).isNull();
    }

    @Test
    void truncatesVeryLongExceptionMessages() {
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            longMessage.append('x');
        }
        ConsumerRecord<String, String> record = new ConsumerRecord<>(EventTopics.ORDER_CREATED_DLT, 0, 1L, "k", "v");
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, utf8(longMessage.toString()));

        assertThat(DltHeaders.toDeadLetterRecord(record).getExceptionMessage())
                .hasSize(DltHeaders.MAX_EXCEPTION_MESSAGE_LENGTH);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
