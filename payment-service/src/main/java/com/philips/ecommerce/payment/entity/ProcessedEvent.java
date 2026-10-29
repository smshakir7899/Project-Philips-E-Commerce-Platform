package com.philips.ecommerce.payment.entity;

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
 * Marker of an already-handled Kafka event (idempotent consumer). Written in the SAME transaction as the business
 * change: if the transaction rolls back, the marker disappears too and the redelivered event is processed again.
 * <p>
 * Implements {@link Persistable}: the id is assigned by us (eventId), so Spring Data's default
 * "id != null means existing" check would call {@code merge()} (a SELECT followed by INSERT). A transient
 * {@code isNew} flag (true for instances we construct, false once loaded or persisted) makes {@code save()} call
 * {@code persist()} - a plain INSERT. If two consumers race on the same event, the primary key rejects the second
 * insert, its transaction rolls back and the retry sees the marker and skips.
 * (An unconditional {@code isNew() == true} would be wrong: Spring Data's {@code delete()} ignores "new" entities.)
 */
@Entity
@Table(name = "processed_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent implements Persistable<String> {

    @Id
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

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

    @Transient
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }
}
