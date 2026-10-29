-- =====================================================================================================
-- V1: product catalog schema (MySQL 8, InnoDB, utf8mb4).
--
-- Index BASELINE of the performance experiment = what this file creates:
--   * PRIMARY KEY of every table (clustered index in InnoDB: rows are physically ordered by id),
--   * UNIQUE constraints uk_category_slug, uk_product_sku (each is a unique B-tree index),
--   * one index per FOREIGN KEY column.
--
-- About the FK indexes: InnoDB REQUIRES an index on a foreign-key column and silently creates one if it is
-- missing, so "no index at all" on product.category_id / product_image.product_id is impossible. We declare
-- them explicitly (idx_product_category, idx_product_image_product, idx_product_attribute_product) because:
--   1. the baseline is then visible and documented instead of implicit, and
--   2. an IMPLICITLY created FK index may be silently dropped by MySQL later when another index with the same
--      leading column is added (V2 adds (category_id, active, price) and (product_id, sort_order)). Then
--      perf/sql/drop-indexes.sql could not drop the V2 index (error 1553 "needed in a foreign key constraint")
--      and the "indexes dropped" scenario would not be reproducible. Explicit indexes are never auto-dropped.
-- Everything else the read workload needs is added in V2__performance_indexes.sql.
-- =====================================================================================================

CREATE TABLE category
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    CONSTRAINT pk_category PRIMARY KEY (id),
    CONSTRAINT uk_category_slug UNIQUE (slug)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product
(
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    sku         VARCHAR(64)    NOT NULL,
    name        VARCHAR(200)   NOT NULL,
    description TEXT           NULL,
    brand       VARCHAR(100)   NOT NULL,
    price       DECIMAL(12, 2) NOT NULL,
    currency    CHAR(3)        NOT NULL DEFAULT 'EUR',
    category_id BIGINT         NOT NULL,
    -- BOOLEAN is an alias of TINYINT(1); Connector/J reports it as BIT, which matches Hibernate's boolean mapping.
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)    NOT NULL,
    updated_at  DATETIME(6)    NOT NULL,
    version     BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_product PRIMARY KEY (id),
    CONSTRAINT uk_product_sku UNIQUE (sku),
    INDEX idx_product_category (category_id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_image
(
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    product_id   BIGINT        NOT NULL,
    s3_key       VARCHAR(512)  NOT NULL,
    url          VARCHAR(1024) NOT NULL,
    content_type VARCHAR(100)  NOT NULL,
    sort_order   INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_product_image PRIMARY KEY (id),
    INDEX idx_product_image_product (product_id),
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE product_attribute
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    product_id BIGINT       NOT NULL,
    attr_name  VARCHAR(100) NOT NULL,
    attr_value VARCHAR(500) NOT NULL,
    CONSTRAINT pk_product_attribute PRIMARY KEY (id),
    INDEX idx_product_attribute_product (product_id),
    CONSTRAINT fk_product_attribute_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
