package com.philips.ecommerce.cart.repository;

import com.philips.ecommerce.cart.entity.Cart;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * cart-service persistence against a REAL MySQL 8.0 (Testcontainers): Flyway schema + Hibernate validate, the
 * fetch-join query, unique constraints and {@code @Version} optimistic locking.
 * NOT_SUPPORTED: every repository call commits on its own, like separate HTTP requests would.
 */
@Testcontainers
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CartMySqlIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("philips_cart").withUsername("philips").withPassword("philips_pwd");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private CartRepository carts;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayMigrationApplied() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void fetchJoinLoadsCartWithItemsInOneQuery() {
        Cart cart = new Cart("CUST-IT-FETCH", "EUR");
        cart.addItem(1L, "PH-SONIC-9900", "Sonicare 9900", new BigDecimal("329.99"), 2);
        cart.addItem(4L, "PH-HUE-E27-START3", "Hue Starter Kit", new BigDecimal("169.99"), 1);
        carts.saveAndFlush(cart);

        Cart loaded = carts.findWithItemsByCustomerId("CUST-IT-FETCH").get();
        // No open transaction here: touching the collection would throw LazyInitializationException if it had
        // not been fetched by the join.
        assertThat(loaded.getItems()).hasSize(2);
    }

    @Test
    void oneCartPerCustomerIsEnforcedByMySql() {
        carts.saveAndFlush(new Cart("CUST-IT-UNIQUE", "EUR"));
        assertThatThrownBy(() -> carts.saveAndFlush(new Cart("CUST-IT-UNIQUE", "EUR")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentUpdatesOfTheSameCartAreDetectedByVersion() {
        carts.saveAndFlush(new Cart("CUST-IT-VERSION", "EUR"));
        // two "requests" read the cart (with its items, as CartService does) at version 0
        Cart first = carts.findWithItemsByCustomerId("CUST-IT-VERSION").get();
        Cart second = carts.findWithItemsByCustomerId("CUST-IT-VERSION").get();

        // Hibernate does NOT bump the parent's @Version when only an inverse (mappedBy) collection changes, so
        // CartService calls touch() (a real column change) after every modification - mirrored here.
        first.addItem(1L, "PH-SONIC-9900", "Sonicare 9900", new BigDecimal("329.99"), 1);
        first.touch();
        carts.saveAndFlush(first);                // version -> 1

        second.addItem(2L, "PH-SONIC-4300", "Sonicare 4300", new BigDecimal("59.99"), 1);
        second.touch();
        assertThatThrownBy(() -> carts.saveAndFlush(second))   // still version 0 -> stale
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
