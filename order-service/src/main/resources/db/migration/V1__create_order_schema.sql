-- order-service schema (MySQL 8). Owned by Flyway; Hibernate only validates it (ddl-auto: validate).

CREATE TABLE orders (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    order_number      VARCHAR(32)    NOT NULL,
    customer_id       VARCHAR(64)    NOT NULL,
    idempotency_key   VARCHAR(100)   NOT NULL,
    status            VARCHAR(32)    NOT NULL,
    total_amount      DECIMAL(12, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    payment_reference VARCHAR(32)    NULL,
    failure_reason    VARCHAR(500)   NULL,
    ship_full_name    VARCHAR(200)   NOT NULL,
    ship_line1        VARCHAR(255)   NOT NULL,
    ship_city         VARCHAR(100)   NOT NULL,
    ship_postal_code  VARCHAR(20)    NOT NULL,
    ship_country      VARCHAR(100)   NOT NULL,
    created_at        DATETIME(6)    NOT NULL,
    updated_at        DATETIME(6)    NOT NULL,
    version           BIGINT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_order_number UNIQUE (order_number),
    -- The unique constraint, not a "SELECT then INSERT", is what makes concurrent checkout retries safe.
    CONSTRAINT uk_orders_idempotency_key UNIQUE (idempotency_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Order history of a customer, newest first: WHERE customer_id = ? ORDER BY created_at DESC
CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at);

CREATE TABLE order_item (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    order_id     BIGINT         NOT NULL,
    product_id   BIGINT         NOT NULL,
    sku          VARCHAR(64)    NOT NULL,
    product_name VARCHAR(200)   NOT NULL,
    unit_price   DECIMAL(12, 2) NOT NULL,
    quantity     INT            NOT NULL,
    line_total   DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE order_status_history (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    from_status VARCHAR(32)  NULL,
    to_status   VARCHAR(32)  NOT NULL,
    reason      VARCHAR(500) NULL,
    changed_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Transactional outbox: rows are written in the same transaction as the order change, then relayed to Kafka.
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
    attempts       INT           NOT NULL,
    last_error     VARCHAR(1000) NULL,
    created_at     DATETIME(6)   NOT NULL,
    sent_at        DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Publisher query: WHERE status = 'PENDING' ORDER BY id LIMIT n FOR UPDATE SKIP LOCKED
CREATE INDEX idx_outbox_status_id ON outbox_event (status, id);
