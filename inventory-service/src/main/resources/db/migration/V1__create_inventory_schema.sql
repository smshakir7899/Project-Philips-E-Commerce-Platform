-- inventory-service schema (database philips_inventory). Owned by Flyway; Hibernate only validates it.

CREATE TABLE inventory (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    product_id          BIGINT       NOT NULL,
    sku                 VARCHAR(64)  NOT NULL,
    available_quantity  INT          NOT NULL,
    reserved_quantity   INT          NOT NULL DEFAULT 0,
    low_stock_threshold INT          NOT NULL DEFAULT 5,
    -- optimistic lock counter (JPA @Version)
    version             BIGINT       NOT NULL DEFAULT 0,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_product UNIQUE (product_id),
    CONSTRAINT uk_inventory_sku UNIQUE (sku),
    -- last line of defence against overselling bugs (enforced by MySQL 8.0.16+)
    CONSTRAINT chk_inventory_available CHECK (available_quantity >= 0),
    CONSTRAINT chk_inventory_reserved CHECK (reserved_quantity >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE stock_reservation (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    -- idempotency key of POST /api/inventory/reservations
    order_number VARCHAR(40) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    expires_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reservation_order_number UNIQUE (order_number)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Expiry job: WHERE status = 'RESERVED' AND expires_at < ? ORDER BY expires_at
-- (equality column first, range/sort column second -> index range scan, no filesort).
CREATE INDEX idx_reservation_status_expires ON stock_reservation (status, expires_at);

CREATE TABLE stock_reservation_item (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    product_id     BIGINT NOT NULL,
    quantity       INT    NOT NULL,
    PRIMARY KEY (id),
    -- duplicate product lines are merged by the service; the key also serves the FK lookup by reservation_id
    CONSTRAINT uk_reservation_item_product UNIQUE (reservation_id, product_id),
    CONSTRAINT fk_reservation_item_reservation FOREIGN KEY (reservation_id) REFERENCES stock_reservation (id),
    CONSTRAINT chk_reservation_item_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Idempotent Kafka consumer bookkeeping (one row per applied event).
CREATE TABLE processed_event (
    event_id     VARCHAR(36)  NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    topic        VARCHAR(100) NOT NULL,
    processed_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
