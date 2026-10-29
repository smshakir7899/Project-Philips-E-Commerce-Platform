package com.philips.ecommerce.order.repository;

import com.philips.ecommerce.order.dto.OrderSummaryResponse;
import com.philips.ecommerce.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    /**
     * Order + items in ONE query (fetch join) for GET /api/orders/{orderNumber}; "distinct" removes the duplicated
     * parent rows a join produces. Only ONE bag collection may be fetch-joined at a time (Hibernate would throw
     * MultipleBagFetchException), hence a separate query for the status history.
     */
    @Query("select distinct o from Order o left join fetch o.items where o.orderNumber = :orderNumber")
    Optional<Order> findWithItemsByOrderNumber(@Param("orderNumber") String orderNumber);

    @Query("select distinct o from Order o left join fetch o.items where o.idempotencyKey = :key")
    Optional<Order> findWithItemsByIdempotencyKey(@Param("key") String idempotencyKey);

    @Query("select distinct o from Order o left join fetch o.statusHistory where o.orderNumber = :orderNumber")
    Optional<Order> findWithHistoryByOrderNumber(@Param("orderNumber") String orderNumber);

    /**
     * Order history page as a DTO projection. {@code size(o.items)} becomes a correlated
     * {@code (select count(*) from order_item ...)} sub-select, so the item count costs no extra round-trip per row
     * (avoids N+1). Ordering is fixed (newest first, id as tie-breaker for stable paging) and served by the
     * (customer_id, created_at) index. The explicit countQuery keeps the count query free of the projection.
     */
    @Query(value = "select new com.philips.ecommerce.order.dto.OrderSummaryResponse("
            + "o.orderNumber, o.status, o.totalAmount, o.currency, size(o.items), o.createdAt) "
            + "from Order o where o.customerId = :customerId order by o.createdAt desc, o.id desc",
            countQuery = "select count(o) from Order o where o.customerId = :customerId")
    Page<OrderSummaryResponse> findSummariesByCustomerId(@Param("customerId") String customerId, Pageable pageable);
}
