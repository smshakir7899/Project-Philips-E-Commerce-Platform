package com.philips.ecommerce.notification.service;

import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.notification.client.EmailSender;
import com.philips.ecommerce.notification.client.dto.EmailMessage;
import com.philips.ecommerce.notification.config.NotificationProperties;
import com.philips.ecommerce.notification.entity.Notification;
import com.philips.ecommerce.notification.entity.NotificationChannel;
import com.philips.ecommerce.notification.entity.NotificationStatus;
import com.philips.ecommerce.notification.entity.NotificationType;
import com.philips.ecommerce.notification.exception.NotificationDeliveryException;
import com.philips.ecommerce.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository repository;
    @Mock
    private EmailSender emailSender;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository, new NotificationTemplateService(), emailSender,
                new NotificationProperties());
    }

    @Test
    void newEventIsSentAndStoredAsSent() {
        when(repository.findByEventId("evt-1")).thenReturn(Optional.<Notification>empty());

        boolean sent = service.handle(confirmed("evt-1", "CUST-1001"));

        assertThat(sent).isTrue();
        ArgumentCaptor<EmailMessage> email = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(email.capture());
        assertThat(email.getValue().getTo()).isEqualTo("CUST-1001@example.com");

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(saved.getValue().getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(saved.getValue().getEventId()).isEqualTo("evt-1");
        assertThat(saved.getValue().getOrderNumber()).isEqualTo("ORD-1");
    }

    @Test
    void duplicateOfSentEventIsIgnored() {
        Notification alreadySent = Notification.builder().id(5L).eventId("evt-1").status(NotificationStatus.SENT).build();
        when(repository.findByEventId("evt-1")).thenReturn(Optional.of(alreadySent));

        boolean sent = service.handle(confirmed("evt-1", "CUST-1001"));

        assertThat(sent).isFalse();
        verifyNoInteractions(emailSender);
        verify(repository, never()).save(any(Notification.class));
    }

    @Test
    void concurrentInsertOfSameEventIsTreatedAsDuplicate() {
        when(repository.findByEventId("evt-1")).thenReturn(Optional.<Notification>empty());
        when(repository.save(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notification_event_id"));

        boolean sent = service.handle(confirmed("evt-1", "CUST-1001"));

        assertThat(sent).isFalse();
    }

    @Test
    void deliveryFailureIsStoredAsFailedAndRethrownForRetry() {
        when(repository.findByEventId("evt-2")).thenReturn(Optional.<Notification>empty());
        doThrow(new NotificationDeliveryException("provider down")).when(emailSender).send(any(EmailMessage.class));

        assertThatThrownBy(() -> service.handle(confirmed("evt-2", "CUST-FAIL")))
                .isInstanceOf(NotificationDeliveryException.class);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void previouslyFailedEventIsRetriedAndUpdatedToSent() {
        Notification failed = Notification.builder().id(9L).eventId("evt-3").customerId("CUST-1")
                .orderNumber("ORD-1").recipient("CUST-1@example.com").subject("s").message("m")
                .type(NotificationType.ORDER_CONFIRMED).channel(NotificationChannel.EMAIL)
                .status(NotificationStatus.FAILED).build();
        when(repository.findByEventId("evt-3")).thenReturn(Optional.of(failed));

        boolean sent = service.handle(confirmed("evt-3", "CUST-1"));

        assertThat(sent).isTrue();
        verify(emailSender, times(1)).send(any(EmailMessage.class));
        verify(repository).save(failed);
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void eventWithoutIdIsRejectedAsNonRetryable() {
        assertThatThrownBy(() -> service.handle(confirmed(null, "CUST-1")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, emailSender);
    }

    private OrderConfirmedEvent confirmed(String eventId, String customerId) {
        return OrderConfirmedEvent.builder().eventId(eventId).orderNumber("ORD-1").customerId(customerId)
                .totalAmount(new BigDecimal("10.00")).currency("EUR").paymentReference("PAY-1").build();
    }
}
