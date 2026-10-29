package com.philips.ecommerce.payment.repository;

import com.philips.ecommerce.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentReference(String paymentReference);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /** Latest attempt of an order (served by idx_payment_order_number). */
    Optional<Payment> findFirstByOrderNumberOrderByCreatedAtDescIdDesc(String orderNumber);

    List<Payment> findByOrderNumberOrderByIdAsc(String orderNumber);
}
