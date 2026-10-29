package com.philips.ecommerce.notification.repository;

import com.philips.ecommerce.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    /** Served by index (customer_id, created_at). */
    Page<Notification> findByCustomerId(String customerId, Pageable pageable);

    /** Chronological story of one order (placed -> payment -> confirmed / cancelled). */
    List<Notification> findByOrderNumberOrderByCreatedAtAscIdAsc(String orderNumber);
}
