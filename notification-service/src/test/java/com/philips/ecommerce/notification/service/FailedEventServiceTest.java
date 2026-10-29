package com.philips.ecommerce.notification.service;

import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.notification.config.NotificationProperties;
import com.philips.ecommerce.notification.dto.DeadLetterRecord;
import com.philips.ecommerce.notification.dto.FailedEventResponse;
import com.philips.ecommerce.notification.entity.FailedEvent;
import com.philips.ecommerce.notification.entity.FailedEventStatus;
import com.philips.ecommerce.notification.kafka.EventTopics;
import com.philips.ecommerce.notification.mapper.NotificationMapper;
import com.philips.ecommerce.notification.repository.FailedEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FailedEventServiceTest {

    private static final Instant NOW = Instant.parse("2024-05-01T10:15:30Z");

    @Mock
    private FailedEventRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private FailedEventService service;

    @BeforeEach
    void setUp() {
        service = new FailedEventService(repository, kafkaTemplate, new NotificationMapper(),
                new NotificationProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayRepublishesPayloadWithOriginalKeyAndMarksReplayed() {
        FailedEvent failedEvent = failedEvent(FailedEventStatus.NEW);
        when(repository.findById(10L)).thenReturn(Optional.of(failedEvent));
        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        future.set(new SendResult<String, String>(null,
                new RecordMetadata(new TopicPartition(KafkaTopics.ORDER_CONFIRMED, 1), 0L, 0, 0L, 0, 0)));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        FailedEventResponse response = service.replay(10L);

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(sent.capture());
        ProducerRecord<String, String> record = sent.getValue();
        assertThat(record.topic()).isEqualTo(KafkaTopics.ORDER_CONFIRMED);
        assertThat(record.key()).isEqualTo("ORD-1");
        assertThat(record.value()).isEqualTo("{\"eventId\":\"e1\"}");
        assertThat(header(record, EventTopics.HEADER_REPLAYED_FROM_DLT)).isEqualTo("true");
        assertThat(header(record, KafkaTopics.HEADER_EVENT_TYPE)).isEqualTo(OrderConfirmedEvent.TYPE);
        assertThat(header(record, KafkaTopics.HEADER_CORRELATION_ID)).isNotEmpty();

        verify(repository).save(failedEvent);
        assertThat(response.getStatus()).isEqualTo(FailedEventStatus.REPLAYED);
        assertThat(response.getReplayedAt()).isEqualTo(NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayFailureLeavesEventNewAndReturns503() {
        FailedEvent failedEvent = failedEvent(FailedEventStatus.NEW);
        when(repository.findById(10L)).thenReturn(Optional.of(failedEvent));
        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        future.setException(new IllegalStateException("broker down"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        assertThatThrownBy(() -> service.replay(10L))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Could not republish");
        assertThat(failedEvent.getStatus()).isEqualTo(FailedEventStatus.NEW);
        verify(repository, never()).save(any(FailedEvent.class));
    }

    @Test
    void replayOfUnknownIdIs404() {
        when(repository.findById(99L)).thenReturn(Optional.<FailedEvent>empty());

        assertThatThrownBy(() -> service.replay(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting("code").isEqualTo("FAILED_EVENT_NOT_FOUND");
    }

    @Test
    void replayingTwiceIsAConflict() {
        when(repository.findById(10L)).thenReturn(Optional.of(failedEvent(FailedEventStatus.REPLAYED)));

        assertThatThrownBy(() -> service.replay(10L))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("FAILED_EVENT_ALREADY_REPLAYED");
    }

    @Test
    void recordStoresNewDeadLetter() {
        DeadLetterRecord deadLetter = deadLetter();
        when(repository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffsetAndConsumerGroup(
                "order-confirmed", 1, 42L, "cart-service")).thenReturn(false);

        boolean stored = service.record(deadLetter);

        assertThat(stored).isTrue();
        ArgumentCaptor<FailedEvent> captor = ArgumentCaptor.forClass(FailedEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(FailedEventStatus.NEW);
        assertThat(captor.getValue().getConsumerGroup()).isEqualTo("cart-service");
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void recordIgnoresRedeliveredDeadLetter() {
        when(repository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffsetAndConsumerGroup(
                eq("order-confirmed"), eq(1), anyLong(), eq("cart-service"))).thenReturn(true);

        assertThat(service.record(deadLetter())).isFalse();
        verify(repository, never()).save(any(FailedEvent.class));
    }

    @Test
    void recordTreatsUniqueConstraintRaceAsDuplicate() {
        when(repository.save(any(FailedEvent.class))).thenThrow(new DataIntegrityViolationException("uk"));

        assertThat(service.record(deadLetter())).isFalse();
    }

    private DeadLetterRecord deadLetter() {
        return DeadLetterRecord.builder().originalTopic("order-confirmed").originalPartition(1).originalOffset(42L)
                .messageKey("ORD-1").payload("{}").exceptionClass("X").exceptionMessage("boom")
                .consumerGroup("cart-service").build();
    }

    private FailedEvent failedEvent(FailedEventStatus status) {
        return FailedEvent.builder().id(10L).originalTopic(KafkaTopics.ORDER_CONFIRMED).originalPartition(1)
                .originalOffset(42L).messageKey("ORD-1").payload("{\"eventId\":\"e1\"}")
                .exceptionClass("X").exceptionMessage("boom").consumerGroup("notification-service")
                .status(status).createdAt(NOW.minusSeconds(60))
                .replayedAt(status == FailedEventStatus.REPLAYED ? NOW.minusSeconds(30) : null)
                .build();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
