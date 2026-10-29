-- =====================================================================================================
-- (Re)creates exactly the indexes of V2__performance_indexes.sql. Idempotent: an index that already exists
-- is skipped (checked in information_schema, since MySQL has no "CREATE INDEX IF NOT EXISTS").
-- Keep the definitions identical to V2 - Flyway does not re-run V2, this script is the only way back after
-- drop-indexes.sql.
-- =====================================================================================================

DROP PROCEDURE IF EXISTS perf_create_index_if_missing;

DELIMITER //
CREATE PROCEDURE perf_create_index_if_missing(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = p_table
                     AND index_name = p_index) THEN
        SET @perf_ddl = p_ddl;
        PREPARE perf_stmt FROM @perf_ddl;
        EXECUTE perf_stmt;
        DEALLOCATE PREPARE perf_stmt;
        SELECT CONCAT('created ', p_table, '.', p_index) AS result;
    ELSE
        SELECT CONCAT('exists  ', p_table, '.', p_index) AS result;
    END IF;
END //
DELIMITER ;

CALL perf_create_index_if_missing('product', 'idx_product_cat_active_price',
     'CREATE INDEX idx_product_cat_active_price ON product (category_id, active, price)');
CALL perf_create_index_if_missing('product', 'idx_product_cat_active_name',
     'CREATE INDEX idx_product_cat_active_name ON product (category_id, active, name)');
CALL perf_create_index_if_missing('product', 'idx_product_active_name',
     'CREATE INDEX idx_product_active_name ON product (active, name)');
CALL perf_create_index_if_missing('product', 'idx_product_active_created',
     'CREATE INDEX idx_product_active_created ON product (active, created_at)');
CALL perf_create_index_if_missing('product', 'ft_product_search',
     'CREATE FULLTEXT INDEX ft_product_search ON product (name, brand, description)');
CALL perf_create_index_if_missing('product_image', 'idx_product_image_product_sort',
     'CREATE INDEX idx_product_image_product_sort ON product_image (product_id, sort_order)');

DROP PROCEDURE perf_create_index_if_missing;

ANALYZE TABLE product, product_image;
