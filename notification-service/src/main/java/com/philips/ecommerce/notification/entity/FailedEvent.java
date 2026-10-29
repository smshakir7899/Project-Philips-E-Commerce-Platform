package com.philips.ecommerce.notification.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
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
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * A record that some consumer (of ANY service) could not process after all retries, captured from a
 * {@code <topic>.DLT} topic together with the diagnostic headers added by Spring Kafka's
 * {@code DeadLetterPublishingRecoverer}.
 *
 * <p>The unique key (original topic, partition, offset, consumer group) identifies one failure of one consumer
 * group: it makes the DLT listener itself idempotent (a redelivered DLT record does not create a second row) while
 * still allowing two different groups (e.g. inventory-service and cart-service) to dead-letter the same record.</p>
 */
@Entity
@Table(name = "failed_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_failed_event_origin",
                columnNames = {"original_topic", "original_partition", "original_offset", "consumer_group"}),
        indexes = @Index(name = "idx_failed_event_status_created", columnList = "status, created_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FailedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_topic", nullable = false, length = 255)
    private String originalTopic;

    @Column(name = "original_partition")
    private Integer originalPartition;

    @Column(name = "original_offset")
    private Long originalOffset;

    @Column(name = "message_key", length = 255)
    private String messageKey;

    /** Raw JSON exactly as it was on the original topic, so a replay is byte-for-byte the same event. */
    @Column(name = "payload", columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "exception_class", length = 255)
    private String exceptionClass;

    @Column(name = "exception_message", length = 2000)
    private String exceptionMessage;

    @Column(name = "consumer_group", length = 255)
    private String consumerGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FailedEventStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = FailedEventStatus.NEW;
        }
    }

    public void markReplayed(Instant when) {
        this.status = FailedEventStatus.REPLAYED;
        this.replayedAt = when;
    }
}
