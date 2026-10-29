package com.philips.ecommerce.order.outbox;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.Instant;

/**
 * One message waiting to be published to Kafka (transactional outbox pattern).
 * <p>
 * The row is inserted in the SAME local database transaction as the business change (e.g. "order CONFIRMED"),
 * so either both are committed or neither is. {@link OutboxPublisher} later relays PENDING rows to Kafka.
 * <p>
 * The index on (status, id) serves the publisher's "oldest PENDING rows first" query without a full table scan.
 */
@Entity
@Table(name = "outbox_event", indexes = {
        @Index(name = "idx_outbox_status_id", columnList = "status, id"),
        @Index(name = "uk_outbox_event_id", columnList = "event_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business event id (also inside the payload); consumers use it for idempotent processing. */
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    /** Kafka record key (orderNumber) - keeps all events of one order in the same partition, i.e. ordered. */
    @Column(name = "message_key", nullable = false, length = 64)
    private String messageKey;

    /** JSON payload. @Lob maps to LONGTEXT on MySQL and CLOB on H2. */
    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    /**
     * Correlation id of the HTTP request that caused the event. Captured at enqueue time because the publisher
     * runs later on a scheduler thread where the request's MDC is no longer available.
     */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    public OutboxEvent(String eventId, String aggregateType, String aggregateId, String eventType, String topic,
                       String messageKey, String payload, String correlationId) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.correlationId = correlationId;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void markSent(Instant when) {
        this.status = OutboxStatus.SENT;
        this.sentAt = when;
        this.lastError = null;
    }

    /**
     * Records a failed publish attempt. After {@code maxAttempts} the row is parked as FAILED so a poison message
     * does not block the publisher forever; an operator can inspect it and retry via the admin endpoint.
     */
    public void markAttemptFailed(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
        }
    }

    /** Admin "retry": put the row back in the queue with a fresh attempt budget. */
    public void resetForRetry() {
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.lastError = null;
        this.sentAt = null;
    }
}
