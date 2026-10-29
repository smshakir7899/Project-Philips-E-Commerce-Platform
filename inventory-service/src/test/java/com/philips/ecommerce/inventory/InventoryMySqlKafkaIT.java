package com.philips.ecommerce.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.common.event.OrderCancelledEvent;
import com.philips.ecommerce.common.event.OrderConfirmedEvent;
import com.philips.ecommerce.common.exception.BusinessException;
import com.philips.ecommerce.inventory.dto.ReservationItemRequest;
import com.philips.ecommerce.inventory.dto.ReservationRequest;
import com.philips.ecommerce.inventory.entity.Inventory;
import com.philips.ecommerce.inventory.entity.ReservationStatus;
import com.philips.ecommerce.inventory.repository.InventoryRepository;
import com.philips.ecommerce.inventory.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * inventory-service against REAL infrastructure (Testcontainers): MySQL 8.0 with the Flyway schema + seed data, and
 * a ZooKeeper-mode Kafka broker from the same Confluent image as docker-compose.
 * <ul>
 *   <li>Concurrent reservations of the last units are serialized by InnoDB row locks
 *       ({@code SELECT ... FOR UPDATE}) - exactly one succeeds, stock never goes negative.</li>
 *   <li>order-cancelled delivered TWICE with the same eventId releases the stock once (idempotent consumer).</li>
 *   <li>order-confirmed commits the reservation.</li>
 * </ul>
 * Run with {@code ./mvnw -pl inventory-service -Pintegration-tests verify} (needs Docker).
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@DirtiesContext
class InventoryMySqlKafkaIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("philips_inventory")
            .withUsername("philips")
            .withPassword("philips_pwd");

    /** Classic KafkaContainer = broker + embedded ZooKeeper (the KRaft-only replacement would not match prod). */
    @SuppressWarnings("deprecation")
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.4"))
            .withEmbeddedZookeeper();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private ReservationService reservationService;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywaySeededTheDemoStock() {
        assertThat(inventoryRepository.count()).isEqualTo(20);
        assertThat(inventoryRepository.findByProductId(5L).get().getAvailableQuantity()).isEqualTo(2);
        assertThat(inventoryRepository.findByProductId(7L).get().getAvailableQuantity()).isZero();
    }

    @Test
    void concurrentReservationsForTheLastUnitsDoNotOversell() throws Exception {
        // product 6 seeded with 60: reduce it to 2 so two orders of 2 compete for the same units
        jdbc.update("UPDATE inventory SET available_quantity = 2, reserved_quantity = 0 WHERE product_id = 6");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final String orderNumber = "ORD-RACE-" + i + "-" + UUID.randomUUID().toString().substring(0, 6);
            results.add(pool.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    start.await();
                    try {
                        reservationService.reserve(new ReservationRequest(orderNumber,
                                Collections.singletonList(new ReservationItemRequest(6L, 2))));
                        return "RESERVED";
                    } catch (BusinessException e) {
                        return e.getCode();
                    }
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).containsExactlyInAnyOrder("RESERVED", "INSUFFICIENT_STOCK");
        Inventory inventory = inventoryRepository.findByProductId(6L).get();
        assertThat(inventory.getAvailableQuantity()).isZero();
        assertThat(inventory.getReservedQuantity()).isEqualTo(2);
    }

    @Test
    void duplicateOrderCancelledEventReleasesStockOnlyOnce() throws Exception {
        String orderNumber = "ORD-IT-CANCEL-" + UUID.randomUUID().toString().substring(0, 8);
        int before = inventoryRepository.findByProductId(1L).get().getAvailableQuantity();
        reservationService.reserve(new ReservationRequest(orderNumber,
                Collections.singletonList(new ReservationItemRequest(1L, 3))));
        assertThat(inventoryRepository.findByProductId(1L).get().getAvailableQuantity()).isEqualTo(before - 3);

        String eventId = UUID.randomUUID().toString();
        String payload = objectMapper.writeValueAsString(OrderCancelledEvent.builder()
                .eventId(eventId).occurredAt(Instant.now()).orderNumber(orderNumber).customerId("CUST-IT")
                .previousStatus("INVENTORY_RESERVED").reason(CancellationReason.PAYMENT_DECLINED).build());
        // at-least-once delivery: the same event arrives twice
        kafkaTemplate.send(KafkaTopics.ORDER_CANCELLED, orderNumber, payload).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(KafkaTopics.ORDER_CANCELLED, orderNumber, payload).get(10, TimeUnit.SECONDS);

        await().atMost(45, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(reservationService.getReservation(orderNumber).getStatus())
                    .isEqualTo(ReservationStatus.RELEASED);
            Integer processed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
            assertThat(processed).isEqualTo(1);
        });
        // give the duplicate time to be consumed, then prove it had no effect
        TimeUnit.SECONDS.sleep(2);
        assertThat(inventoryRepository.findByProductId(1L).get().getAvailableQuantity()).isEqualTo(before);
    }

    @Test
    void orderConfirmedEventCommitsTheReservation() throws Exception {
        String orderNumber = "ORD-IT-CONFIRM-" + UUID.randomUUID().toString().substring(0, 8);
        int before = inventoryRepository.findByProductId(2L).get().getAvailableQuantity();
        reservationService.reserve(new ReservationRequest(orderNumber,
                Collections.singletonList(new ReservationItemRequest(2L, 4))));

        String payload = objectMapper.writeValueAsString(OrderConfirmedEvent.builder()
                .eventId(UUID.randomUUID().toString()).occurredAt(Instant.now()).orderNumber(orderNumber)
                .customerId("CUST-IT").paymentReference("PAY-IT").build());
        kafkaTemplate.send(KafkaTopics.ORDER_CONFIRMED, orderNumber, payload).get(10, TimeUnit.SECONDS);

        await().atMost(45, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(reservationService.getReservation(orderNumber).getStatus())
                        .isEqualTo(ReservationStatus.COMMITTED));
        Inventory inventory = inventoryRepository.findByProductId(2L).get();
        assertThat(inventory.getAvailableQuantity()).isEqualTo(before - 4);
        assertThat(inventory.getReservedQuantity()).isZero();
    }
}
