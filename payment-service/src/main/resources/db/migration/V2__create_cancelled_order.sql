-- Tombstones for orders that were cancelled BEFORE any payment for them reached payment-service.
-- Closes the race "order-service timed out and cancelled, the delayed payment request arrives afterwards":
-- PaymentService checks this table before charging and refuses (FAILED ORDER_ALREADY_CANCELLED).
CREATE TABLE cancelled_order (
    order_number VARCHAR(32) NOT NULL,
    reason       VARCHAR(32) NOT NULL,
    cancelled_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_number)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
