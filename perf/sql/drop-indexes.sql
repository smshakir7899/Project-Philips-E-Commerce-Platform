-- =====================================================================================================
-- Removes the indexes created by V2__performance_indexes.sql (exact inverse) to measure the
-- "no performance indexes" baseline (scenario S1). PK, UNIQUE and FK indexes from V1 stay - InnoDB requires
-- the FK ones anyway (see the V1 header).
--
-- MySQL has no "DROP INDEX IF EXISTS", so a helper procedure checks information_schema first: the script can be
-- run any number of times. Restore with create-indexes.sql (run-benchmark.sh always does that on exit).
-- WARNING: while ft_product_search is dropped, the OPTIMIZED search fails (MySQL error 1191 "Can't find
-- FULLTEXT index"); only the baseline code may run against this schema.
-- =====================================================================================================

DROP PROCEDURE IF EXISTS perf_drop_index_if_exists;

DELIMITER //
CREATE PROCEDURE perf_drop_index_if_exists(IN p_table VARCHAR(64), IN p_index VARCHAR(64))
BEGIN
    IF EXISTS (SELECT 1
               FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name = p_table
                 AND index_name = p_index) THEN
        SET @perf_ddl = CONCAT('DROP INDEX `', p_index, '` ON `', p_table, '`');
        PREPARE perf_stmt FROM @perf_ddl;
        EXECUTE perf_stmt;
        DEALLOCATE PREPARE perf_stmt;
        SELECT CONCAT('dropped ', p_table, '.', p_index) AS result;
    ELSE
        SELECT CONCAT('absent  ', p_table, '.', p_index) AS result;
    END IF;
END //
DELIMITER ;

CALL perf_drop_index_if_exists('product', 'idx_product_cat_active_price');
CALL perf_drop_index_if_exists('product', 'idx_product_cat_active_name');
CALL perf_drop_index_if_exists('product', 'idx_product_active_name');
CALL perf_drop_index_if_exists('product', 'idx_product_active_created');
CALL perf_drop_index_if_exists('product', 'ft_product_search');
CALL perf_drop_index_if_exists('product_image', 'idx_product_image_product_sort');

DROP PROCEDURE perf_drop_index_if_exists;

ANALYZE TABLE product, product_image;
