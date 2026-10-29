package com.philips.ecommerce.order.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Production fetch (MySQL 8+ / PostgreSQL): locks the next batch of PENDING rows for the current transaction.
     * <p>
     * {@code FOR UPDATE} takes row locks so no other publisher can send the same rows concurrently;
     * {@code SKIP LOCKED} makes a second instance skip rows that are already locked instead of WAITING for them.
     * Result: several order-service instances can run the publisher at the same time, each working on a disjoint
     * batch, without a leader election and without double-sending in the normal case.
     * <p>
     * Native SQL because JPQL has no portable SKIP LOCKED syntax.
     */
    @Query(value = "SELECT * FROM outbox_event WHERE status = 'PENDING' ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> lockNextPendingBatch(@Param("limit") int limit);

    /**
     * Portable fetch WITHOUT row locks (works on H2). Only safe with a single publisher instance - used by tests
     * and selectable with {@code app.outbox.fetch-mode=SIMPLE}.
     */
    @Query("select e from OutboxEvent e where e.status = :status order by e.id asc")
    List<OutboxEvent> findBatchByStatus(@Param("status") OutboxStatus status, Pageable pageable);

    List<OutboxEvent> findTop100ByStatusOrderByIdDesc(OutboxStatus status);

    List<OutboxEvent> findTop100ByOrderByIdDesc();

    long countByStatus(OutboxStatus status);
}
