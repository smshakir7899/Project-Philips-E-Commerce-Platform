package com.philips.ecommerce.notification.service;

import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.common.web.CorrelationId;
import com.philips.ecommerce.notification.config.NotificationProperties;
import com.philips.ecommerce.notification.dto.DeadLetterRecord;
import com.philips.ecommerce.notification.dto.FailedEventResponse;
import com.philips.ecommerce.notification.entity.FailedEvent;
import com.philips.ecommerce.notification.entity.FailedEventStatus;
import com.philips.ecommerce.notification.kafka.EventTopics;
import com.philips.ecommerce.notification.mapper.NotificationMapper;
import com.philips.ecommerce.notification.repository.FailedEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Stores dead-lettered records and replays them on request.
 *
 * <p><b>Why replay is safe.</b> A replay republishes the payload, with its original key, to the ORIGINAL topic. Every
 * consumer group subscribed to that topic receives it again - not only the group that failed. That is harmless
 * because every consumer in the platform is idempotent on {@code eventId} (processed_event table, or the unique
 * event_id of notification): groups that already processed the event skip it, and only the group that failed
 * actually does the work. Keeping the original key keeps the record on the same partition, so per-order ordering is
 * preserved relative to later events of that order.</p>
 */
@Service
public class FailedEventService {

    private static final Logger log = LoggerFactory.getLogger(FailedEventService.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final FailedEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final NotificationMapper mapper;
    private final NotificationProperties properties;
    private final Clock clock;

    public FailedEventService(FailedEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                              NotificationMapper mapper, NotificationProperties properties, Clock clock) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Persists one dead letter. Idempotent: a redelivered DLT record (same original topic/partition/offset/group)
     * is ignored, first by an existence check, and by the unique constraint if two deliveries race.
     *
     * @return true if a new row was stored
     */
    public boolean record(DeadLetterRecord deadLetter) {
        if (deadLetter.getOriginalOffset() != null
                && repository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffsetAndConsumerGroup(
                deadLetter.getOriginalTopic(), deadLetter.getOriginalPartition(), deadLetter.getOriginalOffset(),
                deadLetter.getConsumerGroup())) {
            log.info("Dead letter {}-{}@{} of group {} already stored - ignored", deadLetter.getOriginalTopic(),
                    deadLetter.getOriginalPartition(), deadLetter.getOriginalOffset(), deadLetter.getConsumerGroup());
            return false;
        }
        FailedEvent failedEvent = FailedEvent.builder()
                .originalTopic(deadLetter.getOriginalTopic())
                .originalPartition(deadLetter.getOriginalPartition())
                .originalOffset(deadLetter.getOriginalOffset())
                .messageKey(deadLetter.getMessageKey())
                .payload(deadLetter.getPayload())
                .exceptionClass(deadLetter.getExceptionClass())
                .exceptionMessage(deadLetter.getExceptionMessage())
                .consumerGroup(deadLetter.getConsumerGroup())
                .status(FailedEventStatus.NEW)
                .createdAt(Instant.now(clock))
                .build();
        try {
            repository.save(failedEvent);
        } catch (DataIntegrityViolationException e) {
            log.info("Dead letter {}-{}@{} stored concurrently - ignored", deadLetter.getOriginalTopic(),
                    deadLetter.getOriginalPartition(), deadLetter.getOriginalOffset());
            return false;
        }
        log.warn("Stored dead letter #{} from {} (group {}, key {}): {} {}", failedEvent.getId(),
                deadLetter.getOriginalTopic(), deadLetter.getConsumerGroup(), deadLetter.getMessageKey(),
                deadLetter.getExceptionClass(), deadLetter.getExceptionMessage());
        return true;
    }

    /** Newest first, capped at {@code app.notification.failed-events-max-results}; status null = all. */
    public List<FailedEventResponse> list(FailedEventStatus status) {
        PageRequest firstPage = PageRequest.of(0, properties.getFailedEventsMaxResults(), NEWEST_FIRST);
        List<FailedEvent> rows = status != null
                ? repository.findByStatus(status, firstPage)
                : repository.findAllBy(firstPage);
        return rows.stream().map(mapper::toResponse).collect(Collectors.toList());
    }

    /**
     * Republishes the payload to the original topic and marks the row REPLAYED.
     *
     * <p>Ordering: send first (synchronously), then update the row. If the update failed after a successful send,
     * the row would stay NEW and a second replay would deliver the event again - harmless thanks to idempotent
     * consumers. The opposite order could mark an event REPLAYED that never reached Kafka (silent loss).
     * No DB transaction is held open while waiting for the broker.</p>
     */
    public FailedEventResponse replay(Long id) {
        FailedEvent failedEvent = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FAILED_EVENT_NOT_FOUND",
                        "Failed event " + id + " not found"));
        if (failedEvent.getStatus() == FailedEventStatus.REPLAYED) {
            throw new ConflictException("FAILED_EVENT_ALREADY_REPLAYED",
                    "Failed event " + id + " was already replayed at " + failedEvent.getReplayedAt());
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(failedEvent.getOriginalTopic(), null,
                failedEvent.getMessageKey(), failedEvent.getPayload());
        record.headers().add(EventTopics.HEADER_REPLAYED_FROM_DLT, "true".getBytes(StandardCharsets.UTF_8));
        String eventType = EventTopics.eventTypeFor(failedEvent.getOriginalTopic());
        if (eventType != null) {
            record.headers().add(KafkaTopics.HEADER_EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
        }
        // Links the replayed processing in every consumer's logs to the operator's HTTP request
        String correlationId = CorrelationId.current() != null ? CorrelationId.current() : CorrelationId.newId();
        record.headers().add(KafkaTopics.HEADER_CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));

        SendResult<String, String> result = sendSynchronously(record, id);

        failedEvent.markReplayed(Instant.now(clock));
        repository.save(failedEvent);
        log.info("Replayed failed event #{} to {}-{}@{} (key {})", id, failedEvent.getOriginalTopic(),
                result.getRecordMetadata().partition(), result.getRecordMetadata().offset(),
                failedEvent.getMessageKey());
        return mapper.toResponse(failedEvent);
    }

    private SendResult<String, String> sendSynchronously(ProducerRecord<String, String> record, Long id) {
        try {
            return kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("KAFKA_UNAVAILABLE",
                    "Interrupted while replaying failed event " + id, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new ServiceUnavailableException("KAFKA_UNAVAILABLE",
                    "Could not republish failed event " + id + " to " + record.topic(), e);
        }
    }
}
