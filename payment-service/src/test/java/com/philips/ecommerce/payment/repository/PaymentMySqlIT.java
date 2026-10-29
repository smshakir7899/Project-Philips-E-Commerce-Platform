package com.philips.ecommerce.payment.repository;

import com.philips.ecommerce.common.event.KafkaTopics;
import com.philips.ecommerce.payment.entity.Payment;
import com.philips.ecommerce.payment.outbox.OutboxEvent;
import com.philips.ecommerce.payment.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * payment-service persistence against a REAL MySQL 8.0 (Testcontainers): both Flyway migrations, the unique
 * idempotency key, and {@code FOR UPDATE SKIP LOCKED} behaving as documented when two outbox publishers poll at once.
 */
@Testcontainers
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentMySqlIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("philips_payment").withUsername("philips").withPassword("philips_pwd");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private PaymentRepository payments;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void bothFlywayMigrationsApplied() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class)).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'cancelled_order'", Integer.class)).isEqualTo(1);
    }

    @Test
    void duplicateIdempotencyKeyIsRejectedByMySql() {
        payments.saveAndFlush(new Payment("PAY-IT0000000001", "ORD-IT-1", "CUST-IT", new BigDecimal("10.00"),
                "EUR", "tok_visa", "PAY-ORD-IT-1"));
        assertThatThrownBy(() -> payments.saveAndFlush(new Payment("PAY-IT0000000002", "ORD-IT-1", "CUST-IT",
                new BigDecimal("10.00"), "EUR", "tok_visa", "PAY-ORD-IT-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void secondPublisherSkipsOutboxRowsLockedByTheFirst() throws Exception {
        outbox.deleteAll();
        List<OutboxEvent> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = UUID.randomUUID().toString();
            rows.add(new OutboxEvent(id, "Payment", "ORD-IT-SKIP", "PaymentProcessedEvent",
                    KafkaTopics.PAYMENT_PROCESSED, "ORD-IT-SKIP", "{}", "corr-it"));
        }
        outbox.saveAll(rows);

        final TransactionTemplate tx = new TransactionTemplate(transactionManager);
        final CountDownLatch firstHoldsLocks = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicReference<List<Long>> firstIds = new AtomicReference<>();

        Thread publisherA = new Thread(() -> tx.execute(status -> {
            firstIds.set(ids(outbox.lockNextPendingBatch(2)));
            firstHoldsLocks.countDown();
            try {
                releaseFirst.await(20, TimeUnit.SECONDS);   // keep the row locks open
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        publisherA.start();
        assertThat(firstHoldsLocks.await(20, TimeUnit.SECONDS)).isTrue();

        // Publisher B does not wait for A's locks: it skips them and gets only the remaining row.
        long startNanos = System.nanoTime();
        List<Long> secondIds = tx.execute(status -> ids(outbox.lockNextPendingBatch(10)));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        releaseFirst.countDown();
        publisherA.join(20000);

        assertThat(firstIds.get()).hasSize(2);
        assertThat(secondIds).hasSize(1).doesNotContainAnyElementsOf(firstIds.get());
        assertThat(waitedMs).isLessThan(5000);   // no lock wait (innodb_lock_wait_timeout would be 50 s)
    }

    private static List<Long> ids(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::getId).collect(Collectors.toList());
    }
}
