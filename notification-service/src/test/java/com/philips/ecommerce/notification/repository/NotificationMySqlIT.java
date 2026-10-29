package com.philips.ecommerce.notification.repository;

import com.philips.ecommerce.notification.entity.Notification;
import com.philips.ecommerce.notification.entity.NotificationChannel;
import com.philips.ecommerce.notification.entity.NotificationStatus;
import com.philips.ecommerce.notification.entity.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * notification-service persistence against a REAL MySQL 8.0 (Testcontainers): Flyway + Hibernate validate, the
 * unique event_id that makes the Kafka consumers idempotent, and the history query using its composite index.
 */
@Testcontainers
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationMySqlIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("philips_notification").withUsername("philips").withPassword("philips_pwd");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sameEventIdCannotBeStoredTwice() {
        notifications.saveAndFlush(notification("evt-it-dup", "CUST-IT-DUP", Instant.now()));
        assertThatThrownBy(() -> notifications.saveAndFlush(notification("evt-it-dup", "CUST-IT-DUP", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void customerHistoryIsPagedNewestFirstAndUsesTheCompositeIndex() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            notifications.save(notification("evt-it-hist-" + i, "CUST-IT-HIST", now.minusSeconds(60L * i)));
        }
        Page<Notification> page = notifications.findByCustomerId("CUST-IT-HIST",
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).extracting(Notification::getEventId)
                .containsExactly("evt-it-hist-0", "evt-it-hist-1");

        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN SELECT * FROM notification "
                + "WHERE customer_id = 'CUST-IT-HIST' ORDER BY created_at DESC LIMIT 2");
        assertThat(plan.get(0).get("key")).isEqualTo("idx_notification_customer_created");
    }

    private static Notification notification(String eventId, String customerId, Instant createdAt) {
        return Notification.builder()
                .eventId(eventId)
                .customerId(customerId)
                .orderNumber("ORD-IT-0001")
                .type(NotificationType.ORDER_CONFIRMED)
                .channel(NotificationChannel.EMAIL)
                .recipient(customerId + "@example.com")
                .subject("Your order is confirmed")
                .message("Thank you")
                .status(NotificationStatus.SENT)
                .createdAt(createdAt)
                .build();
    }
}
