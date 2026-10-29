package com.philips.ecommerce.notification.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke test of the Flyway script against H2 in MySQL mode (the other tests use Hibernate create-drop, so without
 * this the SQL file would only be exercised on a real MySQL). It catches syntax errors and verifies the unique
 * constraints that implement idempotency. Type-level validation against MySQL happens at startup
 * (ddl-auto: validate) and in the Testcontainers integration tests.
 */
class FlywayMigrationTest {

    @Test
    void migrationCreatesTablesAndIdempotencyConstraints() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:flyway_check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");

        MigrateResult result = Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String insertNotification = "INSERT INTO notification (event_id, customer_id, order_number, type, channel, "
                + "recipient, subject, message, status, created_at) "
                + "VALUES ('evt-1', 'CUST-1', 'ORD-1', 'ORDER_PLACED', 'EMAIL', 'c@example.com', 's', 'm', 'SENT', "
                + "CURRENT_TIMESTAMP)";
        jdbc.update(insertNotification);
        assertThatThrownBy(() -> jdbc.update(insertNotification))
                .hasMessageContaining("UK_NOTIFICATION_EVENT_ID".toLowerCase());

        String insertFailed = "INSERT INTO failed_event (original_topic, original_partition, original_offset, "
                + "consumer_group, status, created_at) VALUES ('order-confirmed', 0, 5, 'cart-service', 'NEW', "
                + "CURRENT_TIMESTAMP)";
        jdbc.update(insertFailed);
        assertThatThrownBy(() -> jdbc.update(insertFailed)).hasMessageContaining("uk_failed_event_origin");
        // Same record dead-lettered by ANOTHER consumer group is a separate failure
        jdbc.update(insertFailed.replace("cart-service", "inventory-service"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM failed_event", Integer.class)).isEqualTo(2);
    }
}
