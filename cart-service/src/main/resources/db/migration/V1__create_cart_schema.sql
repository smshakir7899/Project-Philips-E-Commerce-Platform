-- cart-service schema (database philips_cart). Owned by Flyway; Hibernate only validates it.

CREATE TABLE cart (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    customer_id VARCHAR(64) NOT NULL,
    currency    VARCHAR(3)  NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    -- optimistic lock counter (JPA @Version), incremented on every cart change
    version     BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_cart_customer UNIQUE (customer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE cart_item (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    cart_id      BIGINT         NOT NULL,
    product_id   BIGINT         NOT NULL,
    -- price snapshot taken from product-service when the line was added / updated
    sku          VARCHAR(64)    NOT NULL,
    product_name VARCHAR(200)   NOT NULL,
    unit_price   DECIMAL(12, 2) NOT NULL,
    quantity     INT            NOT NULL,
    PRIMARY KEY (id),
    -- one line per product; its left-most column also serves the fetch join / FK lookup by cart_id
    CONSTRAINT uk_cart_item_product UNIQUE (cart_id, product_id),
    CONSTRAINT fk_cart_item_cart FOREIGN KEY (cart_id) REFERENCES cart (id) ON DELETE CASCADE,
    CONSTRAINT chk_cart_item_quantity CHECK (quantity BETWEEN 1 AND 10)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Idempotent Kafka consumer bookkeeping (one row per applied event).
CREATE TABLE processed_event (
    event_id     VARCHAR(36)  NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    topic        VARCHAR(100) NOT NULL,
    processed_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
