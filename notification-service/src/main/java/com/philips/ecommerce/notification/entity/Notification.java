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
 * One customer notification.
 *
 * <p>The UNIQUE constraint on {@code event_id} is the idempotency guard of the Kafka consumers: Kafka delivers
 * at-least-once, so the same event can arrive twice (producer retry, consumer crash before the offset commit,
 * rebalance, DLT replay). The service checks first ({@code findByEventId}); if two deliveries race past that check,
 * the database rejects the second insert. This replaces the generic {@code processed_event} table used by the other
 * consumers, because here the business row itself is keyed by the event.</p>
 *
 * <p>Lombok: {@code @Getter/@Setter} rather than {@code @Data} - a generated equals/hashCode over all fields
 * (including the generated id) breaks JPA entities in Sets and across persist.</p>
 */
@Entity
@Table(name = "notification",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_event_id", columnNames = "event_id"),
        indexes = {
                @Index(name = "idx_notification_customer_created", columnList = "customer_id, created_at"),
                @Index(name = "idx_notification_order_number", columnList = "order_number")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
