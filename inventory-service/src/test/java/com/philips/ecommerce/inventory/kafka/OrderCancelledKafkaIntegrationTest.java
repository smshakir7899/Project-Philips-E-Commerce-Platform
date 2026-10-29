package com.philips.ecommerce.inventory.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.inventory.dto.ReservationItemRequest;
import com.philips.ecommerce.inventory.dto.ReservationRequest;
import com.philips.ecommerce.inventory.entity.Inventory;
import com.philips.ecommerce.inventory.entity.ReservationStatus;
import com.philips.ecommerce.inventory.repository.InventoryRepository;
import com.philips.ecommerce.inventory.repository.ProcessedEventRepository;
import com.philips.ecommerce.inventory.service.ReservationService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end through a real (embedded) Kafka broker: JSON string payload -> @KafkaListener -> idempotent handler ->
 * database, plus the dead-letter path for a poison message.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = {
        KafkaTopics.ORDER_CONFIRMED, KafkaTopics.ORDER_CONFIRMED + KafkaTopics.DLT_SUFFIX,
        KafkaTopics.ORDER_CANCELLED, KafkaTopics.ORDER_CANCELLED + KafkaTopics.DLT_SUFFIX})
@DirtiesContext
class OrderCancelledKafkaIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    void orderCancelledEventReleasesReservedStock() throws Exception {
        String orderNumber = "ORD-20240501-DDDD0001";
        inventoryRepository.saveAndFlush(new Inventory(101L, "SKU-101", 10, 2));
        reservationService.reserve(new ReservationRequest(orderNumber,
                Collections.singletonList(new ReservationItemRequest(101L, 4))));
        assertThat(inventoryRepository.findByProductId(101L).get().getAvailableQuantity()).isEqualTo(6);

        String eventId = UUID.randomUUID().toString();
        OrderCancelledEvent event = OrderCancelledEvent.builder()
                .eventId(eventId)
                .orderNumber(orderNumber)
                .customerId("CUST-1001")
                .previousStatus("INVENTORY_RESERVED")
                .reason(CancellationReason.PAYMENT_DECLINED)
                .build();
        ProducerRecord<String, String> record = new ProducerRecord<>(KafkaTopics.ORDER_CANCELLED, orderNumber,
                objectMapper.writeValueAsString(event));
        record.headers().add(KafkaTopics.HEADER_EVENT_TYPE, OrderCancelledEvent.TYPE.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaTopics.HEADER_CORRELATION_ID, "test-correlation".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Inventory inventory = inventoryRepository.findByProductId(101L).get();
            assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
            assertThat(inventory.getReservedQuantity()).isZero();
            assertThat(reservationService.getReservation(orderNumber).getStatus())
                    .isEqualTo(ReservationStatus.RELEASED);
            assertThat(processedEventRepository.existsById(eventId)).isTrue();
        });
    }

    @Test
    void malformedPayloadIsSentToTheDeadLetterTopicWithoutRetries() throws Exception {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-test-consumer", "false", embeddedKafka);
        props.put("auto.offset.reset", "earliest");
        Consumer<String, String> dltConsumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer();
        try {
            embeddedKafka.consumeFromAnEmbeddedTopic(dltConsumer,
                    KafkaTopics.ORDER_CANCELLED + KafkaTopics.DLT_SUFFIX);

            kafkaTemplate.send(KafkaTopics.ORDER_CANCELLED, "ORD-BROKEN", "{not json").get(10, TimeUnit.SECONDS);

            ConsumerRecord<String, String> dead = KafkaTestUtils.getSingleRecord(dltConsumer,
                    KafkaTopics.ORDER_CANCELLED + KafkaTopics.DLT_SUFFIX, 30000L);
            assertThat(dead.key()).isEqualTo("ORD-BROKEN");
            assertThat(dead.value()).isEqualTo("{not json");
            assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        } finally {
            dltConsumer.close();
        }
    }
}
