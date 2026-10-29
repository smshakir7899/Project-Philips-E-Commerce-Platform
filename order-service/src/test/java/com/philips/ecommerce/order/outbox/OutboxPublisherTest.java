package com.philips.ecommerce.order.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxProperties properties;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new OutboxProperties();
        properties.setFetchMode(OutboxProperties.FetchMode.SIMPLE);
        properties.setMaxAttempts(10);
        properties.setSendTimeoutMs(1000);
        publisher = new OutboxPublisher(repository, kafkaTemplate, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboxEvent event(String eventId, String key) {
        return new OutboxEvent(eventId, "Order", key, "OrderConfirmedEvent", "order-confirmed", key,
                "{\"eventId\":\"" + eventId + "\"}", "corr-1");
    }

    private static SettableListenableFuture<SendResult<String, String>> ok() {
        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        future.set(new SendResult<String, String>(null, null));
        return future;
    }

    private static SettableListenableFuture<SendResult<String, String>> broken() {
        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        future.setException(new org.apache.kafka.common.errors.TimeoutException("broker down"));
        return future;
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulSendMarksSentWithHeaders() {
        OutboxEvent event = event("e-1", "ORD-1");
        when(repository.findBatchByStatus(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(Collections.singletonList(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(ok());

        int sent = publisher.publishPending();

        assertThat(sent).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isEqualTo(NOW);
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("order-confirmed");
        assertThat(record.key()).isEqualTo("ORD-1");
        assertThat(record.value()).contains("e-1");
        assertThat(header(record, "eventType")).isEqualTo("OrderConfirmedEvent");
        assertThat(header(record, "X-Correlation-Id")).isEqualTo("corr-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedSendIncrementsAttemptsAndStaysPending() {
        OutboxEvent event = event("e-2", "ORD-2");
        when(repository.findBatchByStatus(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(Collections.singletonList(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(broken());

        int sent = publisher.publishPending();

        assertThat(sent).isZero();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("broker down");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lastAllowedAttemptParksEventAsFailed() {
        OutboxEvent event = event("e-3", "ORD-3");
        for (int i = 0; i < 9; i++) {
            event.markAttemptFailed("earlier failure", 10);
        }
        when(repository.findBatchByStatus(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(Collections.singletonList(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(broken());

        publisher.publishPending();

        assertThat(event.getAttempts()).isEqualTo(10);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failureHoldsBackLaterEventsOfTheSameKeyToPreserveOrdering() {
        OutboxEvent first = event("e-4", "ORD-4");
        OutboxEvent second = event("e-5", "ORD-4");
        OutboxEvent otherOrder = event("e-6", "ORD-5");
        when(repository.findBatchByStatus(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(Arrays.asList(first, second, otherOrder));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(broken()).thenReturn(ok());

        int sent = publisher.publishPending();

        assertThat(sent).isEqualTo(1);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getAttempts()).isZero();
        assertThat(otherOrder.getStatus()).isEqualTo(OutboxStatus.SENT);
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void skipLockedModeUsesTheLockingNativeQuery() {
        properties.setFetchMode(OutboxProperties.FetchMode.SKIP_LOCKED);
        properties.setBatchSize(25);
        when(repository.lockNextPendingBatch(25)).thenReturn(Collections.<OutboxEvent>emptyList());

        assertThat(publisher.publishPending()).isZero();

        verify(repository).lockNextPendingBatch(25);
        verify(repository, never()).findBatchByStatus(any(OutboxStatus.class), any(Pageable.class));
    }

    @Test
    void retryResetsRow() {
        OutboxEvent event = event("e-7", "ORD-7");
        event.markAttemptFailed("x", 1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);

        event.resetForRetry();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getLastError()).isNull();
    }
}
