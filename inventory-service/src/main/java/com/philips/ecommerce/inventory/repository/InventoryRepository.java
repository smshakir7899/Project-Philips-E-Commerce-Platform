package com.philips.ecommerce.inventory.repository;

import com.philips.ecommerce.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductId(Long productId);

    Optional<Inventory> findBySku(String sku);

    List<Inventory> findAllByProductIdInOrderByProductIdAsc(Collection<Long> productIds);

    /**
     * Loads and row-locks the given products: {@code SELECT ... WHERE product_id IN (...) ORDER BY product_id
     * FOR UPDATE}. Other transactions that want to lock the same rows block until we commit or roll back, so the
     * "check available, then decrement" sequence cannot be interleaved (no overselling).
     *
     * <p>The ORDER BY documents the lock order: every caller passes ids sorted ascending and InnoDB locks rows in
     * index order, so two transactions locking {1, 5} and {5, 1} both lock 1 first and can never deadlock.
     * MySQL waits at most innodb_lock_wait_timeout (default 50 s) for a lock; the JPA lock-timeout hint is not
     * supported by MySQL for FOR UPDATE, so it is not set here.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.productId in :productIds order by i.productId asc")
    List<Inventory> findAllForUpdateByProductIdIn(@Param("productIds") Collection<Long> productIds);
}
