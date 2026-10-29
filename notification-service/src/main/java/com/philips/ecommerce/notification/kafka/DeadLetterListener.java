package com.philips.ecommerce.notification.kafka;

import com.philips.ecommerce.notification.config.KafkaConfig;
import com.philips.ecommerce.notification.service.FailedEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Collects the dead letters of EVERY consumer in the platform. inventory-service, cart-service, payment-service and
 * this service all use a DeadLetterPublishingRecoverer that writes to {@code <topic>.DLT}; the
 * {@code kafka_dlt-original-consumer-group} header tells which group failed.
 *
 * <p>Separate group ({@code notification-service-dlt}) so its offsets are independent from the business listeners,
 * and a separate container factory whose error handler only logs (no DLT of the DLT). Concurrency 1: dead letters
 * are rare and order of storage does not matter, so one thread for 15 partitions is plenty.</p>
 */
@Component
public class DeadLetterListener {

    private final FailedEventService failedEventService;

    public DeadLetterListener(FailedEventService failedEventService) {
        this.failedEventService = failedEventService;
    }

    @KafkaListener(id = "notification-dlt",
            topics = {EventTopics.ORDER_CREATED_DLT, EventTopics.ORDER_CONFIRMED_DLT, EventTopics.ORDER_CANCELLED_DLT,
                    EventTopics.PAYMENT_PROCESSED_DLT, EventTopics.PAYMENT_REFUNDED_DLT},
            groupId = "${app.notification.dlt-group-id:notification-service-dlt}",
            containerFactory = KafkaConfig.DLT_CONTAINER_FACTORY,
            concurrency = "1")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        try (KafkaMdc ignored = KafkaMdc.forRecord(record)) {
            failedEventService.record(DltHeaders.toDeadLetterRecord(record));
        }
    }
}
