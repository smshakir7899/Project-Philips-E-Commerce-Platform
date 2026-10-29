package com.philips.ecommerce.inventory.repository;

import com.philips.ecommerce.inventory.entity.ReservationStatus;
import com.philips.ecommerce.inventory.entity.StockReservation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    /** Read path: reservation + items in ONE query (fetch join), avoiding the N+1 lazy-loading problem. */
    @Query("select distinct r from StockReservation r left join fetch r.items where r.orderNumber = :orderNumber")
    Optional<StockReservation> findWithItemsByOrderNumber(@Param("orderNumber") String orderNumber);

    /**
     * Write path: locks the reservation row (FOR UPDATE) so a commit, a release and the expiry job cannot change
     * the same reservation concurrently. No fetch join here: FOR UPDATE on a join would also lock the item rows
     * and some databases (H2) reject it; the items are loaded lazily inside the same transaction.
     * Lock order everywhere is "reservation row first, then inventory rows by productId" -> no deadlocks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservation r where r.orderNumber = :orderNumber")
    Optional<StockReservation> findForUpdateByOrderNumber(@Param("orderNumber") String orderNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservation r where r.id = :id")
    Optional<StockReservation> findForUpdateById(@Param("id") Long id);

    /**
     * Ids of reservations still RESERVED whose TTL elapsed, oldest first. Served by the composite index
     * (status, expires_at): equality on the first column, range on the second. Only ids are selected (no lock):
     * each one is then locked and re-checked in its own short transaction.
     */
    @Query("select r.id from StockReservation r where r.status = :status and r.expiresAt < :now order by r.expiresAt asc")
    List<Long> findIdsByStatusAndExpiresAtBefore(@Param("status") ReservationStatus status,
                                                 @Param("now") Instant now,
                                                 Pageable pageable);
}
