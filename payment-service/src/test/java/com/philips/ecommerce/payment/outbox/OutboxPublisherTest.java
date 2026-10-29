package com.philips.ecommerce.payment.outbox;

import com.philips.ecommerce.common.event.KafkaTopics;
import org.apache.kafka.clients.producer.ProducerRecord;
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

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxProperties properties;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new OutboxProperties();
        properties.setMaxAttempts(3);
        properties.setSendTimeoutMs(1000);
        properties.setFetchMode(OutboxProperties.FetchMode.SIMPLE);
        publisher = new OutboxPublisher(repository, kafkaTemplate, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboxEvent event(String eventId, String key) {
        return new OutboxEvent(eventId, "Payment", key, "PaymentProcessedEvent", KafkaTopics.PAYMENT_PROCESSED, key,
                "{\"eventId\":\"" + eventId + "\"}", "corr-123");
    }

    private static SettableListenableFuture<SendResult<String, String>> success() {
        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        future.set(new SendResult<>(null, null));
        return future;
    }

    private static SettableListenableFuture<SendResult<String, String>> failure() {
        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        future.setException(new IllegalStateException("broker unavailable"));
        return future;
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulSendMarksEventSentWithHeaders() {
        OutboxEvent event = event("evt-1", "ORD-1");
        when(repository.findBatchByStatus(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(Collections.singletonList(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(success());

        int sent = publisher.publishPending();

        assertThat(sent).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isEqualTo(NOW);
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo(KafkaTopics.PAYMENT_PROCESSED);
        assertThat(record.key()).isEqualTo("ORD-1");
        assertThat(new String(record.headers().lastHeader(KafkaTopics.HEADER_EVENT_TYPE).value(),
                StandardCharsets.UTF_8)).isEqualTo("PaymentProcessedEvent");
        assertThat(new String(record.headers().lastHeader(KafkaTopics.HEADER_CORRELATION_ID).value(),
                StandardCharsets.UTF_8)).isEqualTo("corr-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedSendIncrementsAttemptsAndKeepsPending() {
        OutboxEvent event = event("evt-2", "ORD-2");
        when(repository.findBatchByStatus(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(Collections.singletonList(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failure());

        assertThat(publisher.publishPending()).isZero();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("broker unavailable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventIsParkedAsFailedAfterMaxAttempts() {
        OutboxEvent event = event("evt-3", "ORD-3");
        when(repository.findBatchByStatus(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(Collections.singletonList(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failure());

        for (int i = 0; i < 3; i++) {
            publisher.publishPending();
        }

        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void laterEventsOfAFailedKeyAreHeldBackToPreserveOrdering() {
        OutboxEvent first = event("evt-4", "ORD-4");
        OutboxEvent second = event("evt-5", "ORD-4");
        OutboxEvent other = event("evt-6", "ORD-5");
        when(repository.findBatchByStatus(eq(OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(Arrays.asList(first, second, other));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failure()).thenReturn(success());

        assertThat(publisher.publishPending()).isEqualTo(1);

        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getAttempts()).isZero();
        assertThat(other.getStatus()).isEqualTo(OutboxStatus.SENT);
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void skipLockedModeUsesTheLockingNativeQuery() {
        properties.setFetchMode(OutboxProperties.FetchMode.SKIP_LOCKED);
        properties.setBatchSize(25);
        when(repository.lockNextPendingBatch(25)).thenReturn(Collections.<OutboxEvent>emptyList());

        assertThat(publisher.publishPending()).isZero();

        verify(repository).lockNextPendingBatch(25);
        verify(repository, never()).findBatchByStatus(any(), any());
    }
}
