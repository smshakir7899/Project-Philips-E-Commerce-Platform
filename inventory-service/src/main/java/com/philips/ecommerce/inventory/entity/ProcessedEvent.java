package com.philips.ecommerce.inventory.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PostLoad;
import javax.persistence.PostPersist;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.time.Instant;

/**
 * Idempotent-consumer bookkeeping: one row per Kafka event that has been applied. Kafka guarantees at-least-once
 * delivery (a record is redelivered when the consumer crashes before committing its offset, or after a rebalance),
 * so every consumer must tolerate duplicates. Writing this row in the SAME database transaction as the business
 * change turns at-least-once delivery into an effectively-once effect.
 *
 * <p>Implements {@link Persistable} because the id is assigned (not generated): without it Spring Data's save()
 * would see a non-null id, call merge() and issue a useless SELECT before the INSERT.
 */
@Entity
@Table(name = "processed_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent implements Persistable<String> {

    /** The producer-assigned eventId (UUID). Primary key = the database rejects a second insert of the same event. */
    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean newEntity = true;

    public ProcessedEvent(String eventId, String eventType, String topic, Instant processedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.topic = topic;
        this.processedAt = processedAt;
    }

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        newEntity = false;
    }
}
