package com.philips.ecommerce.payment.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.payment.entity.Payment;
import com.philips.ecommerce.payment.entity.PaymentStatus;
import com.philips.ecommerce.payment.repository.PaymentRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * Full Kafka round trip without external infrastructure: embedded broker + H2.
 * order-cancelled -> listener -> refund (+ outbox row) -> OutboxPublisher -> payment-refunded,
 * and a poison message -> DefaultErrorHandler -> order-cancelled.DLT.
 */
@SpringBootTest(properties = {
        "app.outbox.enabled=true",
        "app.outbox.fetch-mode=SIMPLE",
        "app.outbox.poll-interval-ms=200"
})
@EmbeddedKafka(partitions = 3, topics = KafkaTopics.ORDER_CANCELLED)
class PaymentKafkaIntegrationTest {

    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void orderCancelledRefundsSucceededPaymentAndPublishesPaymentRefunded() throws Exception {
        String orderNumber = "ORD-20260101-1A2B3C4D";
        Payment payment = new Payment("PAY-IT0000000001", orderNumber, "CUST-1001", new BigDecimal("99.00"), "EUR",
                "tok_visa", "PAY-" + orderNumber);
        payment.markSucceeded();
        paymentRepository.save(payment);

        String json = objectMapper.writeValueAsString(OrderCancelledEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .orderNumber(orderNumber)
                .customerId("CUST-1001")
                .previousStatus("CONFIRMED")
                .reason(CancellationReason.CUSTOMER_REQUESTED)
                .build());
        kafkaTemplate.send(KafkaTopics.ORDER_CANCELLED, orderNumber, json).get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(paymentRepository.findByPaymentReference("PAY-IT0000000001").get().getStatus())
                        .isEqualTo(PaymentStatus.REFUNDED));

        ConsumerRecord<String, String> refunded = awaitRecord(KafkaTopics.PAYMENT_REFUNDED, orderNumber);
        assertThat(refunded.value()).contains("PAY-IT0000000001").contains("ORDER_CANCELLED:CUSTOMER_REQUESTED");
        assertThat(new String(refunded.headers().lastHeader(KafkaTopics.HEADER_EVENT_TYPE).value(),
                StandardCharsets.UTF_8)).isEqualTo("PaymentRefundedEvent");
    }

    @Test
    void malformedMessageIsDeadLettered() throws Exception {
        kafkaTemplate.send(KafkaTopics.ORDER_CANCELLED, "ORD-POISON", "{this is not json").get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> dead =
                awaitRecord(KafkaTopics.ORDER_CANCELLED + KafkaTopics.DLT_SUFFIX, "ORD-POISON");
        assertThat(dead.value()).isEqualTo("{this is not json");
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("it-" + UUID.randomUUID(), "false", broker);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            // Plain subscribe: payment-refunded and the DLT are created by the app's NewTopic beans (KafkaAdmin),
            // not by @EmbeddedKafka, so consumeFromAnEmbeddedTopic() would reject them.
            consumer.subscribe(Collections.singletonList(topic));
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return fail("No record with key " + key + " on " + topic);
    }
}
