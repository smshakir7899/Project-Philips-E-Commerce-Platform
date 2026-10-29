package com.philips.ecommerce.inventory.service;

import com.philips.ecommerce.common.exception.BusinessException;
import com.philips.ecommerce.inventory.config.InventoryProperties;
import com.philips.ecommerce.inventory.dto.ReservationItemRequest;
import com.philips.ecommerce.inventory.dto.ReservationRequest;
import com.philips.ecommerce.inventory.dto.ReservationResponse;
import com.philips.ecommerce.inventory.entity.Inventory;
import com.philips.ecommerce.inventory.entity.ReservationStatus;
import com.philips.ecommerce.inventory.entity.StockReservation;
import com.philips.ecommerce.inventory.repository.InventoryRepository;
import com.philips.ecommerce.inventory.repository.StockReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests: repositories and the transaction manager are mocked (TransactionTemplate simply runs the
 * callback), so only the reservation rules are exercised.
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2024-05-01T10:00:00Z");
    private static final String ORDER = "ORD-20240501-AAAA0001";

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private StockReservationRepository reservationRepository;
    @Mock
    private PlatformTransactionManager transactionManager;

    private SimpleMeterRegistry meterRegistry;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ReservationService(inventoryRepository, reservationRepository, new InventoryProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager, meterRegistry);
    }

    // ---------------------------------------------------------------- reserve

    @Test
    void reserveMovesQuantityFromAvailableToReservedAndSetsTtl() {
        Inventory shaver = new Inventory(1L, "SKU-1", 10, 2);
        Inventory brush = new Inventory(2L, "SKU-2", 5, 2);
        when(reservationRepository.findWithItemsByOrderNumber(ORDER)).thenReturn(Optional.empty());
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection())).thenReturn(Arrays.asList(shaver, brush));
        when(reservationRepository.saveAndFlush(any(StockReservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResult result = service.reserve(request(ORDER, item(1L, 2), item(2L, 1)));

        assertThat(result.isCreated()).isTrue();
        ReservationResponse response = result.getReservation();
        assertThat(response.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.getCreatedAt()).isEqualTo(NOW);
        assertThat(response.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(response.getItems()).extracting("productId", "quantity")
                .containsExactly(tuple(1L, 2), tuple(2L, 1));
        assertThat(shaver.getAvailableQuantity()).isEqualTo(8);
        assertThat(shaver.getReservedQuantity()).isEqualTo(2);
        assertThat(brush.getAvailableQuantity()).isEqualTo(4);
        assertThat(brush.getReservedQuantity()).isEqualTo(1);
    }

    @Test
    void insufficientStockOnOneLineRejectsTheWholeReservationAndChangesNothing() {
        Inventory plenty = new Inventory(1L, "SKU-1", 10, 2);
        Inventory scarce = new Inventory(5L, "SKU-5", 2, 5);
        when(reservationRepository.findWithItemsByOrderNumber(ORDER)).thenReturn(Optional.empty());
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection())).thenReturn(Arrays.asList(plenty, scarce));

        assertThatThrownBy(() -> service.reserve(request(ORDER, item(1L, 3), item(5L, 3))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getDetails()).containsEntry("productId", 5L)
                            .containsEntry("requested", 3)
                            .containsEntry("available", 2);
                });

        assertThat(plenty.getAvailableQuantity()).isEqualTo(10);
        assertThat(plenty.getReservedQuantity()).isZero();
        assertThat(scarce.getAvailableQuantity()).isEqualTo(2);
        verify(reservationRepository, never()).saveAndFlush(any(StockReservation.class));
    }

    @Test
    void unknownProductIsRejectedWithInventoryNotFound() {
        when(reservationRepository.findWithItemsByOrderNumber(ORDER)).thenReturn(Optional.empty());
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection()))
                .thenReturn(Arrays.asList(new Inventory(1L, "SKU-1", 10, 2)));

        assertThatThrownBy(() -> service.reserve(request(ORDER, item(1L, 1), item(99L, 1))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVENTORY_NOT_FOUND");
                    assertThat(e.getDetails()).containsEntry("productId", 99L);
                });
        verify(reservationRepository, never()).saveAndFlush(any(StockReservation.class));
    }

    @Test
    void replayOfAKnownOrderNumberReturnsTheStoredReservationWithoutTouchingStock() {
        StockReservation existing = reservation(ReservationStatus.RESERVED, 1L, 2);
        when(reservationRepository.findWithItemsByOrderNumber(ORDER)).thenReturn(Optional.of(existing));

        ReservationResult result = service.reserve(request(ORDER, item(1L, 2)));

        assertThat(result.isCreated()).isFalse();
        assertThat(result.getReservation().getOrderNumber()).isEqualTo(ORDER);
        verify(inventoryRepository, never()).findAllForUpdateByProductIdIn(anyCollection());
    }

    @Test
    void concurrentDuplicateDetectedByUniqueKeyReturnsTheWinnersReservation() {
        Inventory inventory = new Inventory(1L, "SKU-1", 10, 2);
        StockReservation winner = reservation(ReservationStatus.RESERVED, 1L, 2);
        when(reservationRepository.findWithItemsByOrderNumber(ORDER))
                .thenReturn(Optional.<StockReservation>empty(), Optional.of(winner));
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection())).thenReturn(Arrays.asList(inventory));
        when(reservationRepository.saveAndFlush(any(StockReservation.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key uk_reservation_order_number"));

        ReservationResult result = service.reserve(request(ORDER, item(1L, 2)));

        assertThat(result.isCreated()).isFalse();
        assertThat(result.getReservation().getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void duplicateProductLinesAreMergedAndLockedInAscendingProductOrder() {
        Inventory first = new Inventory(1L, "SKU-1", 10, 2);
        Inventory third = new Inventory(3L, "SKU-3", 10, 2);
        when(reservationRepository.findWithItemsByOrderNumber(ORDER)).thenReturn(Optional.empty());
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection())).thenReturn(Arrays.asList(first, third));
        when(reservationRepository.saveAndFlush(any(StockReservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResult result = service.reserve(request(ORDER, item(3L, 2), item(1L, 1), item(3L, 3)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(inventoryRepository).findAllForUpdateByProductIdIn(ids.capture());
        assertThat(new ArrayList<>(ids.getValue())).containsExactly(1L, 3L);
        assertThat(result.getReservation().getItems()).hasSize(2);
        assertThat(third.getReservedQuantity()).isEqualTo(5);
        assertThat(third.getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void mergedLinesAreCheckedTogetherAgainstAvailability() {
        when(reservationRepository.findWithItemsByOrderNumber(ORDER)).thenReturn(Optional.empty());
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection()))
                .thenReturn(Arrays.asList(new Inventory(1L, "SKU-1", 4, 2)));

        // each line alone (3) would fit into 4, together (6) they do not
        assertThatThrownBy(() -> service.reserve(request(ORDER, item(1L, 3), item(1L, 3))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getDetails()).containsEntry("requested", 6).containsEntry("available", 4));
    }

    // ---------------------------------------------------------------- commit

    @Test
    void commitTurnsReservedStockIntoSoldStock() {
        Inventory inventory = reservedInventory(1L, 10, 2);
        StockReservation reservation = reservation(ReservationStatus.RESERVED, 1L, 2);
        when(reservationRepository.findForUpdateByOrderNumber(ORDER)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection())).thenReturn(Arrays.asList(inventory));

        ReservationResponse response = service.commit(ORDER);

        assertThat(response.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(inventory.getReservedQuantity()).isZero();
        assertThat(inventory.getAvailableQuantity()).isEqualTo(8);
    }

    @Test
    void commitIsIdempotent() {
        StockReservation reservation = reservation(ReservationStatus.COMMITTED, 1L, 2);
        when(reservationRepository.findForUpdateByOrderNumber(ORDER)).thenReturn(Optional.of(reservation));

        assertThat(service.commit(ORDER).getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        verify(inventoryRepository, never()).findAllForUpdateByProductIdIn(anyCollection());
    }

    @Test
    void commitOfReleasedReservationIsAConflict() {
        when(reservationRepository.findForUpdateByOrderNumber(ORDER))
                .thenReturn(Optional.of(reservation(ReservationStatus.RELEASED, 1L, 2)));

        assertThatThrownBy(() -> service.commit(ORDER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_RESERVATION_STATE"));
    }

    @Test
    void commitOfExpiredReservationReacquiresStockWhenStillAvailable() {
        Inventory inventory = new Inventory(1L, "SKU-1", 10, 2);
        StockReservation reservation = reservation(ReservationStatus.EXPIRED, 1L, 2);
        when(reservationRepository.findForUpdateByOrderNumber(ORDER)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection())).thenReturn(Arrays.asList(inventory));

        assertThat(service.commit(ORDER).getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(8);
        assertThat(meterRegistry.get("inventory.reservation.expired.commit").tag("outcome", "reacquired")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void commitOfExpiredReservationWithoutStockIsRecordedNotLost() {
        Inventory inventory = new Inventory(1L, "SKU-1", 1, 2);
        StockReservation reservation = reservation(ReservationStatus.EXPIRED, 1L, 2);
        when(reservationRepository.findForUpdateByOrderNumber(ORDER)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection())).thenReturn(Arrays.asList(inventory));

        assertThat(service.commit(ORDER).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(1);
        assertThat(meterRegistry.get("inventory.reservation.expired.commit").tag("outcome", "unfulfilled")
                .counter().count()).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- release

    @Test
    void releaseReturnsReservedStockToAvailable() {
        Inventory inventory = reservedInventory(1L, 10, 2);
        StockReservation reservation = reservation(ReservationStatus.RESERVED, 1L, 2);
        when(reservationRepository.findForUpdateByOrderNumber(ORDER)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection())).thenReturn(Arrays.asList(inventory));

        assertThat(service.release(ORDER).getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
        assertThat(inventory.getReservedQuantity()).isZero();
    }

    @Test
    void releaseOfCommittedReservationRestocks() {
        Inventory inventory = new Inventory(1L, "SKU-1", 8, 2);
        StockReservation reservation = reservation(ReservationStatus.COMMITTED, 1L, 2);
        when(reservationRepository.findForUpdateByOrderNumber(ORDER)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection())).thenReturn(Arrays.asList(inventory));

        assertThat(service.release(ORDER).getStatus()).isEqualTo(ReservationStatus.RETURNED);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
    }

    @Test
    void releaseIsIdempotentForTerminalStates() {
        for (ReservationStatus terminal : Arrays.asList(ReservationStatus.RELEASED, ReservationStatus.RETURNED,
                ReservationStatus.EXPIRED)) {
            when(reservationRepository.findForUpdateByOrderNumber(ORDER))
                    .thenReturn(Optional.of(reservation(terminal, 1L, 2)));

            assertThat(service.release(ORDER).getStatus()).isEqualTo(terminal);
        }
        verify(inventoryRepository, never()).findAllForUpdateByProductIdIn(anyCollection());
    }

    @Test
    void restReleaseOfUnknownOrderIs404ButEventReleaseIsANoOp() {
        when(reservationRepository.findForUpdateByOrderNumber(ORDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.release(ORDER))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RESERVATION_NOT_FOUND"));
        assertThat(service.releaseIfPresent(ORDER)).isEmpty();
    }

    // ---------------------------------------------------------------- expiry

    @Test
    void expireReleasesOverdueReservation() {
        Inventory inventory = reservedInventory(1L, 10, 2);
        StockReservation overdue = reservation(ReservationStatus.RESERVED, 1L, 2,
                NOW.minus(Duration.ofMinutes(30)), NOW.minus(Duration.ofMinutes(15)));
        when(reservationRepository.findForUpdateById(7L)).thenReturn(Optional.of(overdue));
        when(inventoryRepository.findAllForUpdateByProductIdIn(anyCollection())).thenReturn(Arrays.asList(inventory));

        assertThat(service.expireReservation(7L)).isTrue();
        assertThat(overdue.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
        assertThat(inventory.getReservedQuantity()).isZero();
    }

    @Test
    void expireSkipsReservationThatIsNotOverdueOrNoLongerReserved() {
        StockReservation fresh = reservation(ReservationStatus.RESERVED, 1L, 2);
        StockReservation committed = reservation(ReservationStatus.COMMITTED, 1L, 2,
                NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(45)));
        when(reservationRepository.findForUpdateById(1L)).thenReturn(Optional.of(fresh));
        when(reservationRepository.findForUpdateById(2L)).thenReturn(Optional.of(committed));

        assertThat(service.expireReservation(1L)).isFalse();
        assertThat(service.expireReservation(2L)).isFalse();
        assertThat(fresh.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        verify(inventoryRepository, never()).findAllForUpdateByProductIdIn(anyCollection());
    }

    // ---------------------------------------------------------------- helpers

    private static ReservationRequest request(String orderNumber, ReservationItemRequest... items) {
        return new ReservationRequest(orderNumber, Arrays.asList(items));
    }

    private static ReservationItemRequest item(Long productId, int quantity) {
        return new ReservationItemRequest(productId, quantity);
    }

    /** Inventory in the state after {@code reserved} units of {@code total} were reserved. */
    private static Inventory reservedInventory(Long productId, int total, int reserved) {
        Inventory inventory = new Inventory(productId, "SKU-" + productId, total, 2);
        inventory.reserve(reserved);
        return inventory;
    }

    private static StockReservation reservation(ReservationStatus status, Long productId, int quantity) {
        return reservation(status, productId, quantity, NOW, NOW.plus(Duration.ofMinutes(15)));
    }

    private static StockReservation reservation(ReservationStatus status, Long productId, int quantity,
                                                Instant createdAt, Instant expiresAt) {
        StockReservation reservation = new StockReservation(ORDER, createdAt, expiresAt);
        reservation.addItem(productId, quantity);
        reservation.setStatus(status);
        return reservation;
    }
}
