package com.philips.ecommerce.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.common.event.OrderItemPayload;
import com.philips.ecommerce.notification.entity.FailedEvent;
import com.philips.ecommerce.notification.entity.FailedEventStatus;
import com.philips.ecommerce.notification.entity.Notification;
import com.philips.ecommerce.notification.entity.NotificationStatus;
import com.philips.ecommerce.notification.entity.NotificationType;
import com.philips.ecommerce.notification.exception.NotificationDeliveryException;
import com.philips.ecommerce.notification.repository.FailedEventRepository;
import com.philips.ecommerce.notification.repository.NotificationRepository;
import com.philips.ecommerce.notification.service.FailedEventService;
import com.philips.ecommerce.notification.service.SimulatedFailureRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end through a real (embedded, in-JVM) Kafka broker and H2:
 * producer -> topic -> listener container -> service -> DB, including retry, DeadLetterPublishingRecoverer,
 * the DLT listener, and replay. No external infrastructure needed.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {KafkaTopics.ORDER_CREATED, KafkaTopics.ORDER_CONFIRMED, KafkaTopics.ORDER_CANCELLED,
                KafkaTopics.PAYMENT_PROCESSED, KafkaTopics.PAYMENT_REFUNDED})
@DirtiesContext
class NotificationKafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private FailedEventRepository failedEventRepository;
    @Autowired
    private SimulatedFailureRegistry simulatedFailureRegistry;
    @Autowired
    private FailedEventService failedEventService;

    @AfterEach
    void resetSimulation() {
        simulatedFailureRegistry.replaceAll(Collections.<String>emptyList());
    }

    @Test
    void sameEventPublishedTwiceProducesExactlyOneNotification() throws Exception {
        OrderConfirmedEvent event = confirmedEvent("CUST-DUP-1");
        String json = objectMapper.writeValueAsString(event);

        publish(KafkaTopics.ORDER_CONFIRMED, event.getOrderNumber(), json);
        publish(KafkaTopics.ORDER_CONFIRMED, event.getOrderNumber(), json);
        // A later, different event of the same order proves both duplicates have been consumed (same partition,
        // processed in order) before we assert.
        OrderConfirmedEvent marker = confirmedEvent("CUST-DUP-1");
        marker.setOrderNumber(event.getOrderNumber());
        publish(KafkaTopics.ORDER_CONFIRMED, event.getOrderNumber(), objectMapper.writeValueAsString(marker));

        await().atMost(TIMEOUT.getSeconds(), TimeUnit.SECONDS)
                .until(() -> notificationRepository.findByEventId(marker.getEventId()).isPresent());

        List<Notification> forOrder =
                notificationRepository.findByOrderNumberOrderByCreatedAtAscIdAsc(event.getOrderNumber());
        assertThat(forOrder).hasSize(2);
        assertThat(forOrder).filteredOn(n -> n.getEventId().equals(event.getEventId())).hasSize(1);
        Notification notification = notificationRepository.findByEventId(event.getEventId()).get();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(notification.getRecipient()).isEqualTo("CUST-DUP-1@example.com");
    }

    @Test
    void failingCustomerIsRetriedDeadLetteredStoredAndCanBeReplayed() throws Exception {
        simulatedFailureRegistry.replaceAll(Collections.singletonList("CUST-FAIL-1"));
        OrderConfirmedEvent event = confirmedEvent("CUST-FAIL-1");

        publish(KafkaTopics.ORDER_CONFIRMED, event.getOrderNumber(), objectMapper.writeValueAsString(event));

        // retries exhausted -> order-confirmed.DLT -> DLT listener -> failed_event row
        FailedEvent failedEvent = await().atMost(TIMEOUT.getSeconds(), TimeUnit.SECONDS)
                .until(() -> findFailedEvent(event.getOrderNumber()), f -> f != null);

        assertThat(failedEvent.getOriginalTopic()).isEqualTo(KafkaTopics.ORDER_CONFIRMED);
        assertThat(failedEvent.getConsumerGroup()).isEqualTo("notification-service");
        assertThat(failedEvent.getOriginalPartition()).isBetween(0, 2);
        assertThat(failedEvent.getOriginalOffset()).isNotNull();
        assertThat(failedEvent.getExceptionClass()).isEqualTo(NotificationDeliveryException.class.getName());
        assertThat(failedEvent.getExceptionMessage()).contains("Simulated e-mail provider failure");
        assertThat(failedEvent.getPayload()).contains(event.getEventId());
        assertThat(failedEvent.getStatus()).isEqualTo(FailedEventStatus.NEW);
        assertThat(notificationRepository.findByEventId(event.getEventId()).get().getStatus())
                .isEqualTo(NotificationStatus.FAILED);

        // Operator fixes the cause and replays: the FAILED notification becomes SENT
        simulatedFailureRegistry.replaceAll(Collections.<String>emptyList());
        failedEventService.replay(failedEvent.getId());

        await().atMost(TIMEOUT.getSeconds(), TimeUnit.SECONDS).until(() ->
                notificationRepository.findByEventId(event.getEventId()).get().getStatus() == NotificationStatus.SENT);
        assertThat(failedEventRepository.findById(failedEvent.getId()).get().getStatus())
                .isEqualTo(FailedEventStatus.REPLAYED);
    }

    private FailedEvent findFailedEvent(String orderNumber) {
        for (FailedEvent candidate : failedEventRepository.findAll()) {
            if (orderNumber.equals(candidate.getMessageKey())) {
                return candidate;
            }
        }
        return null;
    }

    private void publish(String topic, String key, String json) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json);
        record.headers().add(KafkaTopics.HEADER_CORRELATION_ID, ("it-" + key).getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaTopics.HEADER_EVENT_TYPE,
                OrderConfirmedEvent.TYPE.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private OrderConfirmedEvent confirmedEvent(String customerId) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return OrderConfirmedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .orderNumber("ORD-20240501-" + suffix)
                .customerId(customerId)
                .totalAmount(new BigDecimal("129.98"))
                .currency("EUR")
                .paymentReference("PAY-" + suffix + "0000")
                .items(Collections.singletonList(OrderItemPayload.builder().productId(1L).sku("HX9911")
                        .productName("Sonicare DiamondClean").quantity(2).unitPrice(new BigDecimal("64.99")).build()))
                .build();
    }
}
