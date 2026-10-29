package com.philips.ecommerce.payment.outbox;

import com.philips.ecommerce.common.event.KafkaTopics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relays PENDING outbox rows to Kafka ("polling publisher"; the alternative is log-based CDC such as Debezium).
 * <p>
 * Each poll runs in ONE database transaction: select a batch (row-locked with FOR UPDATE SKIP LOCKED in production),
 * send each row synchronously, and update its status. The row locks are held only for the duration of the batch,
 * which {@code app.outbox.batch-size} keeps short.
 * <p>
 * Delivery is at-least-once: if the JVM dies after Kafka acknowledged a record but before the transaction commits
 * the SENT status, the row is still PENDING and will be sent again. Consumers deduplicate by eventId.
 * <p>
 * Disabled with {@code app.outbox.enabled=false} (e.g. tests without a broker).
 */
@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                           OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * fixedDelay (not fixedRate): the next poll starts N ms after the previous one FINISHED, so polls never overlap
     * inside one instance. {@code @Transactional} works on this method because the scheduler invokes it through the
     * Spring proxy (it is not a self-invocation).
     *
     * @return number of rows successfully sent (useful for tests and metrics)
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public int publishPending() {
        List<OutboxEvent> batch = fetchBatch();
        if (batch.isEmpty()) {
            return 0;
        }
        int sent = 0;
        // If an event for a key fails, later events for the SAME key in this batch are held back, otherwise a
        // consumer could see e.g. "payment-refunded" before the "payment-processed" that failed to send.
        Set<String> blockedKeys = new HashSet<>();
        for (OutboxEvent event : batch) {
            if (blockedKeys.contains(event.getMessageKey())) {
                continue;
            }
            if (send(event)) {
                event.markSent(Instant.now(clock));
                sent++;
            } else {
                blockedKeys.add(event.getMessageKey());
            }
        }
        // Rows are managed entities: their status changes are flushed when this transaction commits.
        log.debug("Outbox: sent {}/{} events", sent, batch.size());
        return sent;
    }

    private List<OutboxEvent> fetchBatch() {
        if (properties.getFetchMode() == OutboxProperties.FetchMode.SKIP_LOCKED) {
            return repository.lockNextPendingBatch(properties.getBatchSize());
        }
        return repository.findBatchByStatus(OutboxStatus.PENDING, PageRequest.of(0, properties.getBatchSize()));
    }

    private boolean send(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
        record.headers().add(KafkaTopics.HEADER_EVENT_TYPE, event.getEventType().getBytes(StandardCharsets.UTF_8));
        if (event.getCorrelationId() != null) {
            record.headers().add(KafkaTopics.HEADER_CORRELATION_ID,
                    event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
        try {
            // Synchronous send: we only mark SENT after the broker acknowledged (acks=all) the record.
            kafkaTemplate.send(record).get(properties.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            log.info("Outbox: published {} {} key={} to {}", event.getEventType(), event.getEventId(),
                    event.getMessageKey(), event.getTopic());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(event, e);
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            recordFailure(event, e);
            return false;
        }
    }

    private void recordFailure(OutboxEvent event, Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        event.markAttemptFailed(cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                properties.getMaxAttempts());
        if (event.getStatus() == OutboxStatus.FAILED) {
            log.error("Outbox: giving up on {} {} after {} attempts: {}", event.getEventType(), event.getEventId(),
                    event.getAttempts(), event.getLastError());
        } else {
            log.warn("Outbox: send of {} {} failed (attempt {}): {}", event.getEventType(), event.getEventId(),
                    event.getAttempts(), event.getLastError());
        }
    }
}
