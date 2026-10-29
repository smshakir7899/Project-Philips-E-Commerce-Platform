package com.philips.ecommerce.payment.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.philips.ecommerce.common.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * Writes events into the outbox table.
 * <p>
 * <b>Why an outbox? (the dual-write problem)</b> A service that updates its database AND publishes to Kafka performs
 * two writes to two different systems without a shared transaction. Whatever the order, a crash between them leaves
 * the system inconsistent: either the payment is REFUNDED but nobody is told (the customer is never notified),
 * or an event announces a change that was rolled back (a "payment SUCCEEDED" that never got committed).
 * 2PC/XA between MySQL and Kafka is not an option (Kafka does not take part in XA, and 2PC hurts availability).
 * Instead we write the event as a ROW in the same local DB transaction as the business change;
 * {@link OutboxPublisher} relays it to Kafka afterwards.
 * <p>
 * Guarantee: <b>at-least-once</b> delivery. The publisher may send a row and crash before marking it SENT, so the same
 * event can be published twice. Every consumer is therefore idempotent (processed_event table keyed by eventId).
 * <p>
 * Note: order-service and payment-service each have their own copy of this package on purpose - sharing persistence
 * code through common-lib would couple the services' schemas and release cycles (service autonomy over DRY).
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    /** The aggregate whose state change produced the events of this service. */
    static final String AGGREGATE_TYPE = "Payment";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Stores an event to be published to {@code topic} with record key {@code key}.
     * <p>
     * {@code Propagation.MANDATORY}: this method refuses to run without an active transaction
     * (throws IllegalTransactionStateException). Writing an outbox row in its OWN transaction would reintroduce the
     * dual-write problem, so a caller that forgot {@code @Transactional} fails fast instead of silently losing
     * atomicity.
     *
     * @param event any Jackson-serializable object; if it has an {@code eventId} property it is reused,
     *              otherwise a random UUID is generated and written into the payload
     * @return the event id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueue(String topic, String key, String eventType, Object event) {
        ObjectNode json = objectMapper.valueToTree(event);
        String eventId = json.path("eventId").asText(null);
        if (!StringUtils.hasText(eventId)) {
            eventId = UUID.randomUUID().toString();
            json.put("eventId", eventId);
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            // Programming error (non-serializable event) - fail the business transaction rather than lose the event.
            throw new IllegalStateException("Cannot serialize event " + eventType, e);
        }
        OutboxEvent row = new OutboxEvent(eventId, AGGREGATE_TYPE, key, eventType, topic, key, payload,
                CorrelationId.current());
        repository.save(row);
        log.debug("Outbox: enqueued {} {} for {} on {}", eventType, eventId, key, topic);
        return eventId;
    }
}
