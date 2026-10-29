-- payment-service schema (MySQL 8). Owned by Flyway; Hibernate only validates it.

CREATE TABLE payment (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    payment_reference VARCHAR(20)   NOT NULL,
    order_number      VARCHAR(32)   NOT NULL,
    customer_id       VARCHAR(64)   NOT NULL,
    amount            DECIMAL(12,2) NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    -- Only the last 4 characters of the card token: the full token is never stored (PCI DSS scope reduction).
    card_token_last4  VARCHAR(4)    NULL,
    status            VARCHAR(16)   NOT NULL,
    failure_reason    VARCHAR(100)  NULL,
    idempotency_key   VARCHAR(100)  NOT NULL,
    cancel_requested  BIT(1)        NOT NULL DEFAULT b'0',
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    version           BIGINT        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_reference UNIQUE (payment_reference),
    CONSTRAINT uk_payment_idempotency_key UNIQUE (idempotency_key),
    INDEX idx_payment_order_number (order_number)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Idempotent consumer markers (one row per handled Kafka event).
CREATE TABLE processed_event (
    event_id     VARCHAR(36)  NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    topic        VARCHAR(100) NOT NULL,
    processed_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Transactional outbox: written in the same transaction as the payment change, relayed to Kafka by OutboxPublisher.
CREATE TABLE outbox_event (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    event_id       VARCHAR(36)   NOT NULL,
    aggregate_type VARCHAR(50)   NOT NULL,
    aggregate_id   VARCHAR(64)   NOT NULL,
    event_type     VARCHAR(100)  NOT NULL,
    topic          VARCHAR(100)  NOT NULL,
    message_key    VARCHAR(64)   NOT NULL,
    payload        LONGTEXT      NOT NULL,
    correlation_id VARCHAR(64)   NULL,
    status         VARCHAR(16)   NOT NULL,
    attempts       INT           NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000) NULL,
    created_at     DATETIME(6)   NOT NULL,
    sent_at        DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
    -- Serves "WHERE status = 'PENDING' ORDER BY id LIMIT n FOR UPDATE SKIP LOCKED".
    INDEX idx_outbox_status_id (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
