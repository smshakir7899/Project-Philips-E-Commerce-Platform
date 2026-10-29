package com.philips.ecommerce.notification.service;

import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.common.event.OrderCreatedEvent;
import com.philips.ecommerce.common.event.PaymentProcessedEvent;
import com.philips.ecommerce.common.event.PaymentRefundedEvent;
import com.philips.ecommerce.notification.client.EmailSender;
import com.philips.ecommerce.notification.client.dto.EmailMessage;
import com.philips.ecommerce.notification.config.NotificationProperties;
import com.philips.ecommerce.notification.dto.RenderedNotification;
import com.philips.ecommerce.notification.entity.Notification;
import com.philips.ecommerce.notification.entity.NotificationChannel;
import com.philips.ecommerce.notification.entity.NotificationStatus;
import com.philips.ecommerce.notification.exception.NotificationDeliveryException;
import com.philips.ecommerce.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Idempotent "event -> notification" processing used by the Kafka listeners.
 *
 * <p>Algorithm for one event:</p>
 * <ol>
 *     <li>Look up the event id. A SENT row means "already done" -> skip (duplicate delivery).
 *         A FAILED row means an earlier attempt failed -> try again and update that row.</li>
 *     <li>Render the text, hand it to the {@link EmailSender}.</li>
 *     <li>Persist the row as SENT. If the insert hits the unique constraint on event_id, another delivery of the same
 *         event won the race -> treat as duplicate.</li>
 *     <li>If sending fails: persist/keep the row as FAILED (visible in the API) and RETHROW, so the listener
 *         container retries and finally dead-letters the record.</li>
 * </ol>
 *
 * <p>Transactions: intentionally no {@code @Transactional} around the whole method. The FAILED row must be committed
 * even though the method then throws (a surrounding transaction would roll it back), and the slow external send
 * should not hold a database connection/transaction open. Each {@code save} is its own short transaction.</p>
 *
 * <p>Guarantee: at-least-once e-mail. If the process dies after the send but before the SENT row is committed, the
 * record is redelivered and the customer gets the e-mail twice. Exactly-once across an external system is not
 * possible without the provider supporting an idempotency key (we would pass the eventId there).</p>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final NotificationTemplateService templates;
    private final EmailSender emailSender;
    private final NotificationProperties properties;

    public NotificationService(NotificationRepository repository, NotificationTemplateService templates,
                               EmailSender emailSender, NotificationProperties properties) {
        this.repository = repository;
        this.templates = templates;
        this.emailSender = emailSender;
        this.properties = properties;
    }

    public boolean handle(OrderCreatedEvent event) {
        requireIds(event.getEventId(), event.getCustomerId(), event.getOrderNumber());
        return deliver(event.getEventId(), event.getCustomerId(), event.getOrderNumber(), templates.orderPlaced(event));
    }

    public boolean handle(OrderConfirmedEvent event) {
        requireIds(event.getEventId(), event.getCustomerId(), event.getOrderNumber());
        return deliver(event.getEventId(), event.getCustomerId(), event.getOrderNumber(),
                templates.orderConfirmed(event));
    }

    public boolean handle(OrderCancelledEvent event) {
        requireIds(event.getEventId(), event.getCustomerId(), event.getOrderNumber());
        return deliver(event.getEventId(), event.getCustomerId(), event.getOrderNumber(),
                templates.orderCancelled(event));
    }

    public boolean handle(PaymentProcessedEvent event) {
        requireIds(event.getEventId(), event.getCustomerId(), event.getOrderNumber());
        if (event.getStatus() == null) {
            throw new IllegalArgumentException("PaymentProcessedEvent " + event.getEventId() + " has no status");
        }
        return deliver(event.getEventId(), event.getCustomerId(), event.getOrderNumber(),
                templates.paymentProcessed(event));
    }

    public boolean handle(PaymentRefundedEvent event) {
        requireIds(event.getEventId(), event.getCustomerId(), event.getOrderNumber());
        return deliver(event.getEventId(), event.getCustomerId(), event.getOrderNumber(),
                templates.paymentRefunded(event));
    }

    /**
     * @return true if a notification was sent now, false if the event had already been handled (duplicate)
     * @throws NotificationDeliveryException if sending failed (the listener container retries / dead-letters)
     */
    boolean deliver(String eventId, String customerId, String orderNumber, RenderedNotification rendered) {
        Optional<Notification> existing = repository.findByEventId(eventId);
        if (existing.isPresent() && existing.get().getStatus() == NotificationStatus.SENT) {
            log.info("Duplicate event {} ({}) ignored - notification {} already sent",
                    eventId, rendered.getType(), existing.get().getId());
            return false;
        }

        Notification notification = existing.isPresent()
                ? existing.get()
                : Notification.builder()
                .eventId(eventId)
                .customerId(customerId)
                .orderNumber(orderNumber)
                .type(rendered.getType())
                .channel(NotificationChannel.EMAIL)
                .recipient(customerId + "@" + properties.getRecipientDomain())
                .subject(rendered.getSubject())
                .message(rendered.getMessage())
                .build();

        try {
            emailSender.send(new EmailMessage(properties.getSenderAddress(), notification.getRecipient(),
                    notification.getSubject(), notification.getMessage(), customerId));
        } catch (NotificationDeliveryException e) {
            notification.setStatus(NotificationStatus.FAILED);
            saveIgnoringDuplicate(notification);
            throw e;
        }

        notification.setStatus(NotificationStatus.SENT);
        if (!saveIgnoringDuplicate(notification)) {
            return false;
        }
        log.info("Notification {} sent for event {} (order {}, customer {})",
                rendered.getType(), eventId, orderNumber, customerId);
        return true;
    }

    /** @return false when a concurrent delivery of the same event already inserted the row (unique constraint). */
    private boolean saveIgnoringDuplicate(Notification notification) {
        try {
            repository.save(notification);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.info("Event {} was stored concurrently by another delivery - treating as duplicate",
                    notification.getEventId());
            return false;
        }
    }

    /**
     * A record without these ids can never succeed, so retrying is pointless: IllegalArgumentException is
     * configured as NOT retryable and goes straight to the DLT.
     */
    private void requireIds(String eventId, String customerId, String orderNumber) {
        if (!StringUtils.hasText(eventId) || !StringUtils.hasText(customerId) || !StringUtils.hasText(orderNumber)) {
            throw new IllegalArgumentException("Event is missing eventId/customerId/orderNumber (eventId="
                    + eventId + ", customerId=" + customerId + ", orderNumber=" + orderNumber + ")");
        }
    }
}
