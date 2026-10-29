-- notification-service schema (database philips_notification). Owned by Flyway; Hibernate only validates it.

CREATE TABLE notification (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    event_id     VARCHAR(64)  NOT NULL,
    customer_id  VARCHAR(64)  NOT NULL,
    order_number VARCHAR(32)  NOT NULL,
    type         VARCHAR(30)  NOT NULL,
    channel      VARCHAR(20)  NOT NULL,
    recipient    VARCHAR(255) NOT NULL,
    subject      VARCHAR(255) NOT NULL,
    message      TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- Idempotency guard for the Kafka consumers: one notification per event, enforced by the database.
    CONSTRAINT uk_notification_event_id UNIQUE (event_id)
);

-- GET /api/notifications?customerId=... ORDER BY created_at DESC  -> index range scan, no filesort
CREATE INDEX idx_notification_customer_created ON notification (customer_id, created_at);
-- GET /api/notifications/order/{orderNumber}
CREATE INDEX idx_notification_order_number ON notification (order_number);

CREATE TABLE failed_event (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    original_topic     VARCHAR(255)  NOT NULL,
    original_partition INT           NULL,
    original_offset    BIGINT        NULL,
    message_key        VARCHAR(255)  NULL,
    payload            LONGTEXT      NULL,
    exception_class    VARCHAR(255)  NULL,
    exception_message  VARCHAR(2000) NULL,
    consumer_group     VARCHAR(255)  NULL,
    status             VARCHAR(20)   NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    replayed_at        DATETIME(6)   NULL,
    PRIMARY KEY (id),
    -- Makes the DLT listener idempotent: one row per (record, failing consumer group).
    CONSTRAINT uk_failed_event_origin UNIQUE (original_topic, original_partition, original_offset, consumer_group)
);

-- GET /api/notifications/admin/failed-events?status=NEW ORDER BY created_at DESC
CREATE INDEX idx_failed_event_status_created ON failed_event (status, created_at);
