package com.philips.ecommerce.payment.repository;

import com.philips.ecommerce.payment.entity.CancelledOrder;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tombstones of orders cancelled before any payment existed (primary key = orderNumber). */
public interface CancelledOrderRepository extends JpaRepository<CancelledOrder, String> {
}
