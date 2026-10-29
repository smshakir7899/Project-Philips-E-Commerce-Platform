package com.philips.ecommerce.payment.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderCancelledListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private OrderCancellationHandler handler;

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(KafkaTopics.ORDER_CANCELLED, 0, 0L, "ORD-1", value);
    }

    @Test
    void parsesEventAndDelegatesToHandler() throws Exception {
        OrderCancelledListener listener = new OrderCancelledListener(objectMapper, handler);
        String json = objectMapper.writeValueAsString(OrderCancelledEvent.builder()
                .eventId("evt-1").orderNumber("ORD-1").occurredAt(Instant.parse("2026-01-01T10:00:00Z"))
                .reason(CancellationReason.PAYMENT_ERROR).build());

        listener.onOrderCancelled(record(json));

        ArgumentCaptor<OrderCancelledEvent> captor = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(handler).handle(captor.capture(), eq(KafkaTopics.ORDER_CANCELLED));
        assertThat(captor.getValue().getEventId()).isEqualTo("evt-1");
        assertThat(captor.getValue().getReason()).isEqualTo(CancellationReason.PAYMENT_ERROR);
    }

    @Test
    void malformedJsonIsRejectedWithoutCallingHandler() {
        OrderCancelledListener listener = new OrderCancelledListener(objectMapper, handler);

        assertThatThrownBy(() -> listener.onOrderCancelled(record("{not json")))
                .isInstanceOf(JsonProcessingException.class);
        verify(handler, never()).handle(any(), anyString());
    }

    @Test
    void eventWithoutIdIsRejectedAsInvalid() {
        OrderCancelledListener listener = new OrderCancelledListener(objectMapper, handler);

        assertThatThrownBy(() -> listener.onOrderCancelled(record("{\"orderNumber\":\"ORD-1\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(handler, never()).handle(any(), anyString());
    }
}
