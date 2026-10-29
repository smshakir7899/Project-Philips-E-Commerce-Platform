package com.philips.ecommerce.inventory.service;

import com.philips.ecommerce.common.exception.BadRequestException;
import com.philips.ecommerce.inventory.config.InventoryProperties;
import com.philips.ecommerce.inventory.dto.ReservationItemRequest;
import com.philips.ecommerce.inventory.dto.ReservationRequest;
import com.philips.ecommerce.inventory.dto.ReservationResponse;
import com.philips.ecommerce.inventory.entity.Inventory;
import com.philips.ecommerce.inventory.entity.ReservationStatus;
import com.philips.ecommerce.inventory.entity.StockReservation;
import com.philips.ecommerce.inventory.entity.StockReservationItem;
import com.philips.ecommerce.inventory.mapper.InventoryMapper;
import com.philips.ecommerce.inventory.repository.InventoryRepository;
import com.philips.ecommerce.inventory.repository.StockReservationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Reservation life cycle: reserve -> commit (sold) or release (cancelled) or expire (TTL).
 *
 * <h3>Concurrency model</h3>
 * Inventory rows are locked with {@code SELECT ... FOR UPDATE} (PESSIMISTIC_WRITE) before they are checked and
 * changed, so two checkouts of the last unit are serialised: the second one waits for the first to commit and
 * then sees the decremented quantity -> no overselling. Locks are always acquired in the same order
 * (reservation row first, then inventory rows ascending by productId), which rules out deadlocks of the kind
 * "T1 locks A then waits for B, T2 locks B then waits for A".
 *
 * <h3>Idempotency</h3>
 * order-service retries the reserve call (timeouts, Resilience4j retry), so the same orderNumber may arrive
 * several times, even concurrently. The unique key on {@code order_number} is the real guarantee; the lookup
 * before inserting is only the fast path.
 */
@Slf4j
@Service
public class ReservationService {

    private final InventoryRepository inventoryRepository;
    private final StockReservationRepository reservationRepository;
    private final InventoryProperties properties;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final Counter expiredCommitReacquired;
    private final Counter expiredCommitUnfulfilled;

    public ReservationService(InventoryRepository inventoryRepository,
                              StockReservationRepository reservationRepository,
                              InventoryProperties properties,
                              Clock clock,
                              PlatformTransactionManager transactionManager,
                              MeterRegistry meterRegistry) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.properties = properties;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.expiredCommitReacquired = Counter.builder("inventory.reservation.expired.commit")
                .description("Commits that arrived after the reservation had expired")
                .tag("outcome", "reacquired")
                .register(meterRegistry);
        this.expiredCommitUnfulfilled = Counter.builder("inventory.reservation.expired.commit")
                .description("Commits that arrived after the reservation had expired")
                .tag("outcome", "unfulfilled")
                .register(meterRegistry);
    }

    /**
     * Reserves all items of an order, all-or-nothing.
     *
     * <p>Not annotated with @Transactional on purpose: transactions are opened programmatically with
     * {@link TransactionTemplate} because a duplicate-key failure ROLLS BACK the transaction it happens in. To
     * recover from the race "two requests with the same orderNumber both passed the lookup" we must catch the
     * {@link DataIntegrityViolationException} OUTSIDE that transaction and read the winner's reservation in a new
     * one. (With @Transactional the exception would only surface in the caller, outside this class.)
     */
    public ReservationResult reserve(ReservationRequest request) {
        final String orderNumber = request.getOrderNumber().trim();
        final SortedMap<Long, Integer> quantities = mergeByProduct(request.getItems());

        ReservationResponse existing = readTransaction.execute(status -> findResponse(orderNumber));
        if (existing != null) {
            log.info("Reservation for order {} already exists (status {}), returning it", orderNumber,
                    existing.getStatus());
            return ReservationResult.replayed(existing);
        }

        try {
            ReservationResponse created = writeTransaction.execute(status -> createReservation(orderNumber, quantities));
            return ReservationResult.created(created);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent request for the same order: return what the winner stored.
            ReservationResponse winner = readTransaction.execute(status -> findResponse(orderNumber));
            if (winner == null) {
                throw e;
            }
            log.info("Concurrent duplicate reservation request for order {} detected, returning the stored one",
                    orderNumber);
            return ReservationResult.replayed(winner);
        }
    }

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(String orderNumber) {
        return reservationRepository.findWithItemsByOrderNumber(orderNumber)
                .map(InventoryMapper::toResponse)
                .orElseThrow(() -> InventoryErrors.reservationNotFound(orderNumber));
    }

    /**
     * The order was paid: reserved stock becomes sold. Idempotent (COMMITTED stays COMMITTED).
     *
     * <p>Commit of an EXPIRED reservation (payment took longer than the TTL, or the event was delayed): the
     * expiry job has already put the stock back, possibly sold it to somebody else. Silently marking it
     * COMMITTED would make the books wrong, rejecting it would block the partition and help nobody since the
     * customer has paid. Chosen behaviour: log an ERROR and try to re-acquire the stock from available
     * (all-or-nothing). If that is impossible the reservation stays EXPIRED, an ERROR is logged and the metric
     * {@code inventory.reservation.expired.commit{outcome=unfulfilled}} is incremented so operations can
     * backorder or refund - the event is still acknowledged, it must not be retried forever.
     */
    @Transactional
    public ReservationResponse commit(String orderNumber) {
        StockReservation reservation = reservationRepository.findForUpdateByOrderNumber(orderNumber)
                .orElseThrow(() -> InventoryErrors.reservationNotFound(orderNumber));

        switch (reservation.getStatus()) {
            case RESERVED:
                Map<Long, Inventory> stock = lockInventories(reservation);
                for (StockReservationItem item : reservation.getItems()) {
                    stock.get(item.getProductId()).commitReserved(item.getQuantity());
                }
                reservation.setStatus(ReservationStatus.COMMITTED);
                log.info("Committed reservation of order {}", orderNumber);
                break;
            case COMMITTED:
                log.info("Reservation of order {} already committed, nothing to do", orderNumber);
                break;
            case EXPIRED:
                commitExpired(reservation);
                break;
            default:
                // RELEASED / RETURNED: the order was cancelled; confirming it now is a saga inconsistency.
                throw InventoryErrors.invalidState(orderNumber, "commit", reservation.getStatus());
        }
        return InventoryMapper.toResponse(reservation);
    }

    /** REST release: unknown order -> 404 RESERVATION_NOT_FOUND. */
    @Transactional
    public ReservationResponse release(String orderNumber) {
        StockReservation reservation = reservationRepository.findForUpdateByOrderNumber(orderNumber)
                .orElseThrow(() -> InventoryErrors.reservationNotFound(orderNumber));
        applyRelease(reservation);
        return InventoryMapper.toResponse(reservation);
    }

    /**
     * Kafka (order-cancelled) release: an unknown order is a normal case - the order failed BEFORE stock was
     * reserved (e.g. INSUFFICIENT_STOCK, or inventory-service was down) - so it is a no-op, not an error.
     */
    @Transactional
    public Optional<ReservationResponse> releaseIfPresent(String orderNumber) {
        Optional<StockReservation> reservation = reservationRepository.findForUpdateByOrderNumber(orderNumber);
        if (!reservation.isPresent()) {
            log.info("No reservation for cancelled order {} (cancelled before reserving), nothing to release",
                    orderNumber);
            return Optional.empty();
        }
        applyRelease(reservation.get());
        return Optional.of(InventoryMapper.toResponse(reservation.get()));
    }

    /**
     * Expires ONE reservation if it is still RESERVED and past its TTL. Runs in its own short transaction per
     * reservation (called by the expiry job through the Spring proxy), and re-checks the state under the row lock
     * because a commit/release may have happened since the job selected the id.
     *
     * @return true when the reservation was expired by this call
     */
    @Transactional
    public boolean expireReservation(Long reservationId) {
        Optional<StockReservation> found = reservationRepository.findForUpdateById(reservationId);
        if (!found.isPresent()) {
            return false;
        }
        StockReservation reservation = found.get();
        if (!reservation.isExpiredAt(clock.instant())) {
            return false;
        }
        Map<Long, Inventory> stock = lockInventories(reservation);
        for (StockReservationItem item : reservation.getItems()) {
            stock.get(item.getProductId()).releaseReserved(item.getQuantity());
        }
        reservation.setStatus(ReservationStatus.EXPIRED);
        log.warn("Reservation of order {} expired (expiresAt {}), stock returned", reservation.getOrderNumber(),
                reservation.getExpiresAt());
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------

    private ReservationResponse findResponse(String orderNumber) {
        return reservationRepository.findWithItemsByOrderNumber(orderNumber)
                .map(InventoryMapper::toResponse)
                .orElse(null);
    }

    /** Runs inside the write transaction opened by {@link #reserve(ReservationRequest)}. */
    private ReservationResponse createReservation(String orderNumber, SortedMap<Long, Integer> quantities) {
        // keySet() of a TreeMap is sorted -> rows are locked in ascending productId order (deadlock avoidance).
        List<Inventory> rows = inventoryRepository.findAllForUpdateByProductIdIn(quantities.keySet());
        Map<Long, Inventory> stock = indexByProductId(rows);

        // 1) Verify EVERYTHING before changing ANYTHING. A single failing line aborts the whole reservation, and the
        //    managed entities stay untouched (no half-applied state even if a caller caught the exception).
        for (Long productId : quantities.keySet()) {
            if (!stock.containsKey(productId)) {
                throw InventoryErrors.inventoryNotFound(productId);
            }
        }
        for (Map.Entry<Long, Integer> line : quantities.entrySet()) {
            Inventory inventory = stock.get(line.getKey());
            if (!inventory.canReserve(line.getValue())) {
                throw InventoryErrors.insufficientStock(line.getKey(), line.getValue(),
                        inventory.getAvailableQuantity());
            }
        }

        // 2) Apply: available -> reserved for every line, and record the reservation.
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofMinutes(properties.getReservationTtlMinutes()));
        StockReservation reservation = new StockReservation(orderNumber, now, expiresAt);
        for (Map.Entry<Long, Integer> line : quantities.entrySet()) {
            Inventory inventory = stock.get(line.getKey());
            inventory.reserve(line.getValue());
            reservation.addItem(line.getKey(), line.getValue());
            if (inventory.isLowStock()) {
                log.warn("Low stock for product {} ({}): {} available, threshold {}", inventory.getProductId(),
                        inventory.getSku(), inventory.getAvailableQuantity(), inventory.getLowStockThreshold());
            }
        }
        // saveAndFlush: the INSERT (and a possible duplicate-key violation) happens now, inside the transaction.
        StockReservation saved = reservationRepository.saveAndFlush(reservation);
        log.info("Reserved stock for order {}: {} (expires {})", orderNumber, quantities, expiresAt);
        return InventoryMapper.toResponse(saved);
    }

    private void applyRelease(StockReservation reservation) {
        String orderNumber = reservation.getOrderNumber();
        switch (reservation.getStatus()) {
            case RESERVED: {
                Map<Long, Inventory> stock = lockInventories(reservation);
                for (StockReservationItem item : reservation.getItems()) {
                    stock.get(item.getProductId()).releaseReserved(item.getQuantity());
                }
                reservation.setStatus(ReservationStatus.RELEASED);
                log.info("Released reservation of order {}", orderNumber);
                break;
            }
            case COMMITTED: {
                // Cancelled after confirmation (e.g. CUSTOMER_REQUESTED): the goods are put back on sale.
                Map<Long, Inventory> stock = lockInventories(reservation);
                for (StockReservationItem item : reservation.getItems()) {
                    stock.get(item.getProductId()).restock(item.getQuantity());
                }
                reservation.setStatus(ReservationStatus.RETURNED);
                log.info("Restocked committed reservation of order {} (status RETURNED)", orderNumber);
                break;
            }
            default:
                // RELEASED, RETURNED, EXPIRED: stock was already given back -> idempotent no-op.
                log.info("Reservation of order {} is already {}, nothing to release", orderNumber,
                        reservation.getStatus());
        }
    }

    private void commitExpired(StockReservation reservation) {
        Map<Long, Inventory> stock = lockInventories(reservation);
        boolean allAvailable = true;
        for (StockReservationItem item : reservation.getItems()) {
            if (!stock.get(item.getProductId()).canReserve(item.getQuantity())) {
                allAvailable = false;
                break;
            }
        }
        if (allAvailable) {
            for (StockReservationItem item : reservation.getItems()) {
                stock.get(item.getProductId()).consumeAvailable(item.getQuantity());
            }
            reservation.setStatus(ReservationStatus.COMMITTED);
            expiredCommitReacquired.increment();
            log.error("Order {} was confirmed after its reservation expired at {}; stock was re-acquired and "
                    + "committed", reservation.getOrderNumber(), reservation.getExpiresAt());
        } else {
            expiredCommitUnfulfilled.increment();
            log.error("Order {} was confirmed after its reservation expired at {} and the stock is no longer "
                            + "available: reservation left EXPIRED, manual intervention (backorder/refund) required",
                    reservation.getOrderNumber(), reservation.getExpiresAt());
        }
    }

    /** Locks the inventory rows of a reservation (ascending productId, see class comment). */
    private Map<Long, Inventory> lockInventories(StockReservation reservation) {
        SortedMap<Long, Integer> ids = new TreeMap<>();
        for (StockReservationItem item : reservation.getItems()) {
            ids.put(item.getProductId(), item.getQuantity());
        }
        Map<Long, Inventory> stock = indexByProductId(inventoryRepository.findAllForUpdateByProductIdIn(ids.keySet()));
        for (Long productId : ids.keySet()) {
            if (!stock.containsKey(productId)) {
                // Inventory rows are never deleted, so this indicates manual data tampering.
                throw new IllegalStateException("Inventory row of product " + productId + " referenced by reservation "
                        + reservation.getOrderNumber() + " is missing");
            }
        }
        return stock;
    }

    /**
     * Merges duplicate product lines ({1:2},{1:3} -> {1:5}) and sorts by productId. Without merging, two lines of
     * the same product would each pass the availability check on their own although together they exceed it.
     */
    static SortedMap<Long, Integer> mergeByProduct(List<ReservationItemRequest> items) {
        SortedMap<Long, Integer> merged = new TreeMap<>();
        for (ReservationItemRequest item : items) {
            Integer current = merged.get(item.getProductId());
            int base = current == null ? 0 : current;
            try {
                merged.put(item.getProductId(), Math.addExact(base, item.getQuantity()));
            } catch (ArithmeticException e) {
                throw new BadRequestException("INVALID_QUANTITY", "Quantity of product " + item.getProductId()
                        + " is too large");
            }
        }
        return merged;
    }

    private static Map<Long, Inventory> indexByProductId(List<Inventory> rows) {
        Map<Long, Inventory> byProduct = new HashMap<>();
        for (Inventory inventory : rows) {
            byProduct.put(inventory.getProductId(), inventory);
        }
        return byProduct;
    }
}
