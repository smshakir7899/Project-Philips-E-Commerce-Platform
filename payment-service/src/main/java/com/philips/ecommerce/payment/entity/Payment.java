package com.philips.ecommerce.payment.entity;

import com.philips.ecommerce.common.exception.ConflictException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * One payment attempt for an order.
 * <p>
 * PCI DSS: we never store the card token / PAN. Only its last 4 characters are kept for display and support.
 * Storing full card data would bring this database (and every system touching it) into PCI DSS scope; a real
 * integration would only ever see a provider-issued token and keep at most the last 4 digits and the brand.
 * <p>
 * Not using Lombok @Data on entities on purpose: generated equals/hashCode over mutable fields and lazy
 * associations break Set semantics and can trigger lazy loading; state changes go through intention-revealing
 * methods instead of setters.
 */
@Entity
@Table(name = "payment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_reference", columnNames = "payment_reference"),
                @UniqueConstraint(name = "uk_payment_idempotency_key", columnNames = "idempotency_key")
        },
        indexes = @Index(name = "idx_payment_order_number", columnList = "order_number"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_reference", nullable = false, length = 20)
    private String paymentReference;

    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Last 4 characters of the card token - the full token is never persisted (see class comment). */
    @Column(name = "card_token_last4", length = 4)
    private String cardTokenLast4;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    /** Client-supplied key (order-service sends "PAY-" + orderNumber); the UNIQUE constraint makes retries safe. */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /**
     * Set when order-cancelled arrives while the gateway call is still running. When the (late) success comes back,
     * the payment is refunded right away instead of keeping money for a cancelled order.
     */
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic locking: the gateway-result transaction and the order-cancelled consumer may update the same row
     * concurrently; the loser gets an OptimisticLockException and re-reads instead of overwriting the other change.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Payment(String paymentReference, String orderNumber, String customerId, BigDecimal amount,
                   String currency, String cardToken, String idempotencyKey) {
        this.paymentReference = paymentReference;
        this.orderNumber = orderNumber;
        this.customerId = customerId;
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.currency = currency;
        this.cardTokenLast4 = lastFour(cardToken);
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.PROCESSING;
        this.cancelRequested = false;
    }

    private static String lastFour(String token) {
        if (token == null) {
            return null;
        }
        return token.length() <= 4 ? token : token.substring(token.length() - 4);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void markSucceeded() {
        requireStatus(PaymentStatus.PROCESSING);
        this.status = PaymentStatus.SUCCEEDED;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        requireStatus(PaymentStatus.PROCESSING);
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }

    /** SUCCEEDED -> REFUNDED; anything else is rejected with 409 INVALID_PAYMENT_STATE. */
    public void markRefunded() {
        requireStatus(PaymentStatus.SUCCEEDED);
        this.status = PaymentStatus.REFUNDED;
    }

    public void requestCancellation() {
        this.cancelRequested = true;
    }

    private void requireStatus(PaymentStatus expected) {
        if (this.status != expected) {
            throw new ConflictException("INVALID_PAYMENT_STATE",
                    "Payment " + paymentReference + " is " + status + ", expected " + expected)
                    .withDetail("paymentReference", paymentReference)
                    .withDetail("status", status.name());
        }
    }
}
