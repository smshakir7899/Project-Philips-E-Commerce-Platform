package com.philips.ecommerce.notification.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.web.CorrelationId;
import com.philips.ecommerce.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Mock
    private NotificationService notificationService;

    @Test
    void parsesPayloadAndPutsCorrelationIdIntoMdcWhileProcessing() throws Exception {
        NotificationEventListener listener = new NotificationEventListener(objectMapper, notificationService);
        OrderCancelledEvent event = OrderCancelledEvent.builder().eventId("evt-9").occurredAt(Instant.now())
                .orderNumber("ORD-9").customerId("CUST-9").reason(CancellationReason.PAYMENT_DECLINED).build();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(KafkaTopics.ORDER_CANCELLED, 1, 7L, "ORD-9",
                objectMapper.writeValueAsString(event));
        record.headers().add(KafkaTopics.HEADER_CORRELATION_ID, "corr-42".getBytes(StandardCharsets.UTF_8));

        AtomicReference<String> mdcDuringProcessing = new AtomicReference<>();
        when(notificationService.handle(any(OrderCancelledEvent.class))).thenAnswer(invocation -> {
            mdcDuringProcessing.set(MDC.get(CorrelationId.MDC_KEY));
            return true;
        });

        listener.onOrderCancelled(record);

        ArgumentCaptor<OrderCancelledEvent> captor = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(notificationService).handle(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo("evt-9");
        assertThat(captor.getValue().getReason()).isEqualTo(CancellationReason.PAYMENT_DECLINED);
        assertThat(mdcDuringProcessing.get()).isEqualTo("corr-42");
        assertThat(MDC.get(CorrelationId.MDC_KEY)).as("MDC cleaned after processing").isNull();
    }

    @Test
    void malformedJsonSurfacesAsJsonProcessingException() {
        NotificationEventListener listener = new NotificationEventListener(objectMapper, notificationService);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(KafkaTopics.ORDER_CREATED, 0, 1L, "ORD-1",
                "{not json");

        assertThatThrownBy(() -> listener.onOrderCreated(record)).isInstanceOf(JsonProcessingException.class);
        verifyNoInteractions(notificationService);
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void nullValueIsRejectedAsNonRetryable() {
        NotificationEventListener listener = new NotificationEventListener(objectMapper, notificationService);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(KafkaTopics.PAYMENT_REFUNDED, 0, 1L, "ORD-1",
                null);

        assertThatThrownBy(() -> listener.onPaymentRefunded(record)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(notificationService);
    }
}
