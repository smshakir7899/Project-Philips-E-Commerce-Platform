package com.philips.ecommerce.inventory.repository;

import com.philips.ecommerce.inventory.entity.Inventory;
import com.philips.ecommerce.inventory.entity.ReservationStatus;
import com.philips.ecommerce.inventory.entity.StockReservation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import javax.persistence.LockModeType;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the custom JPQL against a real (H2, MySQL mode) database: catches typos in queries, wrong sorting and
 * verifies the pessimistic lock mode is applied. (Blocking behaviour itself is a MySQL feature; it is exercised by
 * the Testcontainers-based integration tests, not by H2.)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositoryQueriesTest {

    private static final Instant NOW = Instant.parse("2024-05-01T10:00:00Z");

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private StockReservationRepository reservationRepository;

    @Test
    void lockingQueryReturnsRequestedRowsSortedByProductIdWithPessimisticWriteLock() {
        entityManager.persist(new Inventory(3L, "SKU-3", 30, 5));
        entityManager.persist(new Inventory(1L, "SKU-1", 10, 5));
        entityManager.persist(new Inventory(2L, "SKU-2", 20, 5));
        entityManager.flush();
        entityManager.clear();

        List<Inventory> locked = inventoryRepository.findAllForUpdateByProductIdIn(Arrays.asList(3L, 1L, 99L));

        assertThat(locked).extracting(Inventory::getProductId).containsExactly(1L, 3L);
        for (Inventory inventory : locked) {
            assertThat(entityManager.getEntityManager().getLockMode(inventory))
                    .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        }
    }

    @Test
    void expiryQueryFindsOnlyOverdueReservedReservationsOldestFirstAndHonoursTheBatchSize() {
        Long oldest = persistReservation("ORD-1", ReservationStatus.RESERVED, NOW.minus(Duration.ofMinutes(30)));
        Long older = persistReservation("ORD-2", ReservationStatus.RESERVED, NOW.minus(Duration.ofMinutes(10)));
        persistReservation("ORD-3", ReservationStatus.RESERVED, NOW.minus(Duration.ofMinutes(1)));
        persistReservation("ORD-4", ReservationStatus.RESERVED, NOW.plus(Duration.ofMinutes(5)));
        persistReservation("ORD-5", ReservationStatus.COMMITTED, NOW.minus(Duration.ofMinutes(60)));
        persistReservation("ORD-6", ReservationStatus.RELEASED, NOW.minus(Duration.ofMinutes(60)));
        entityManager.flush();

        List<Long> firstBatch = reservationRepository.findIdsByStatusAndExpiresAtBefore(
                ReservationStatus.RESERVED, NOW, PageRequest.of(0, 2));
        List<Long> all = reservationRepository.findIdsByStatusAndExpiresAtBefore(
                ReservationStatus.RESERVED, NOW, PageRequest.of(0, 100));

        assertThat(firstBatch).containsExactly(oldest, older);
        assertThat(all).hasSize(3);
    }

    @Test
    void fetchJoinLoadsReservationWithItems() {
        StockReservation reservation = new StockReservation("ORD-9", NOW, NOW.plus(Duration.ofMinutes(15)));
        reservation.addItem(1L, 2);
        reservation.addItem(2L, 1);
        entityManager.persist(reservation);
        entityManager.flush();
        entityManager.clear();

        Optional<StockReservation> loaded = reservationRepository.findWithItemsByOrderNumber("ORD-9");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getItems()).extracting("productId").containsExactly(1L, 2L);
        assertThat(reservationRepository.findForUpdateByOrderNumber("ORD-9")).isPresent();
        assertThat(reservationRepository.findWithItemsByOrderNumber("UNKNOWN")).isEmpty();
    }

    private Long persistReservation(String orderNumber, ReservationStatus status, Instant expiresAt) {
        StockReservation reservation = new StockReservation(orderNumber, expiresAt.minus(Duration.ofMinutes(15)),
                expiresAt);
        reservation.addItem(1L, 1);
        reservation.setStatus(status);
        return entityManager.persistAndGetId(reservation, Long.class);
    }
}
