package com.philips.ecommerce.inventory.kafka;

import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.inventory.entity.ProcessedEvent;
import com.philips.ecommerce.inventory.repository.ProcessedEventRepository;
import com.philips.ecommerce.inventory.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Applies saga events exactly once in effect.
 *
 * <p>Kafka delivers at-least-once: if the service crashes after the DB commit but before the offset commit, the
 * record is delivered again. Each method therefore runs ONE local transaction containing
 * (1) the "already processed?" check, (2) the business change and (3) the processed_event insert. Either all three
 * are committed or none: a redelivery after a crash either finds the processed_event row (skip) or finds nothing
 * changed (apply). If two deliveries race (e.g. during a rebalance), the primary key on event_id makes the second
 * transaction fail and roll back, and its retry then sees the row and skips.
 * At-least-once delivery + idempotent consumer = effectively-once processing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventHandler {

    private final ProcessedEventRepository processedEventRepository;
    private final ReservationService reservationService;
    private final Clock clock;

    /** order-confirmed -> commit the reservation (reserved stock is sold). */
    @Transactional
    public boolean onOrderConfirmed(OrderConfirmedEvent event, String topic) {
        if (alreadyProcessed(event.getEventId(), event.getOrderNumber())) {
            return false;
        }
        reservationService.commit(event.getOrderNumber());
        markProcessed(event.getEventId(), OrderConfirmedEvent.TYPE, topic);
        return true;
    }

    /** order-cancelled -> release (RESERVED) or restock (COMMITTED); no-op when nothing was reserved. */
    @Transactional
    public boolean onOrderCancelled(OrderCancelledEvent event, String topic) {
        if (alreadyProcessed(event.getEventId(), event.getOrderNumber())) {
            return false;
        }
        reservationService.releaseIfPresent(event.getOrderNumber());
        markProcessed(event.getEventId(), OrderCancelledEvent.TYPE, topic);
        return true;
    }

    private boolean alreadyProcessed(String eventId, String orderNumber) {
        if (processedEventRepository.existsById(eventId)) {
            log.info("Event {} for order {} already processed, skipping duplicate delivery", eventId, orderNumber);
            return true;
        }
        return false;
    }

    private void markProcessed(String eventId, String eventType, String topic) {
        processedEventRepository.save(new ProcessedEvent(eventId, eventType, topic, clock.instant()));
    }
}
