-- =====================================================================================================
-- EXPLAIN / EXPLAIN ANALYZE of the benchmark workload queries, baseline vs optimized (MySQL 8.0.18+).
-- The SQL below is what Hibernate generates for the two ProductQueryService implementations (simplified
-- aliases; enable logging.level.org.hibernate.SQL=DEBUG to see the exact statements).
--
-- Run it twice and compare: once after drop-indexes.sql, once after create-indexes.sql:
--   docker compose exec -T -e MYSQL_PWD=root_pwd mysql mysql -uroot -t philips_product < perf/sql/explain-queries.sql
-- What to look for:
--   * type=ALL / "Table scan" and large "rows" -> full scan;  type=ref/range + "Index range scan" -> index used
--   * "Using filesort" / "Sort:" -> rows sorted after reading; absent when the index delivers the ORDER BY
--   * EXPLAIN ANALYZE prints the ACTUAL rows and time per plan node (it executes the query).
-- =====================================================================================================

SELECT 'Q1 list by category, ORDER BY name (default sort), page 3' AS query;
-- identical SQL in both modes -> the difference is purely the index idx_product_cat_active_name
EXPLAIN FORMAT = TREE
SELECT p.id, p.sku, p.name, p.brand, p.price, p.currency, p.category_id, p.active
FROM product p
WHERE p.active = TRUE AND p.category_id = 3
ORDER BY p.name ASC, p.id ASC
LIMIT 40, 20;
EXPLAIN ANALYZE
SELECT p.id FROM product p WHERE p.active = TRUE AND p.category_id = 3 ORDER BY p.name ASC, p.id ASC LIMIT 40, 20;

SELECT 'Q2 category + price range, ORDER BY price' AS query;
-- served by idx_product_cat_active_price: two equalities + range, rows already in price order
EXPLAIN FORMAT = TREE
SELECT p.id FROM product p
WHERE p.active = TRUE AND p.category_id = 4 AND p.price >= 100 AND p.price <= 200
ORDER BY p.price ASC, p.id ASC
LIMIT 0, 20;
EXPLAIN ANALYZE
SELECT p.id FROM product p
WHERE p.active = TRUE AND p.category_id = 4 AND p.price >= 100 AND p.price <= 200
ORDER BY p.price ASC, p.id ASC
LIMIT 0, 20;

SELECT 'Q2c count query of Q2 (Spring Data runs it for every non-final page)' AS query;
EXPLAIN ANALYZE
SELECT COUNT(p.id) FROM product p WHERE p.active = TRUE AND p.category_id = 4 AND p.price >= 100 AND p.price <= 200;

SELECT 'Q3a BASELINE search: LOWER(col) LIKE %term% -> full table scan, cannot use any B-tree index' AS query;
EXPLAIN ANALYZE
SELECT p.id FROM product p
WHERE p.active = TRUE
  AND (LOWER(p.name) LIKE '%espresso%' ESCAPE '!' OR LOWER(p.description) LIKE '%espresso%' ESCAPE '!')
ORDER BY p.name ASC, p.id ASC
LIMIT 0, 20;
EXPLAIN ANALYZE
SELECT COUNT(p.id) FROM product p
WHERE p.active = TRUE
  AND (LOWER(p.name) LIKE '%espresso%' ESCAPE '!' OR LOWER(p.description) LIKE '%espresso%' ESCAPE '!');

SELECT 'Q3b OPTIMIZED search: MATCH ... AGAINST (BOOLEAN MODE) -> "Full-text index search" on ft_product_search' AS query;
EXPLAIN ANALYZE
SELECT p.id FROM product p
WHERE p.active = TRUE
  AND MATCH(p.name, p.brand, p.description) AGAINST ('+espresso*' IN BOOLEAN MODE)
ORDER BY p.name ASC, p.id ASC
LIMIT 0, 20;
EXPLAIN ANALYZE
SELECT COUNT(*) FROM product p
WHERE p.active = TRUE AND MATCH(p.name, p.brand, p.description) AGAINST ('+espresso*' IN BOOLEAN MODE);

SELECT 'Q4a BASELINE thumbnails: N+1 - this statement runs ONCE PER PRODUCT of the page (20x)' AS query;
EXPLAIN ANALYZE
SELECT i.id, i.product_id, i.s3_key, i.url, i.content_type, i.sort_order
FROM product_image i
WHERE i.product_id = 100003
ORDER BY i.sort_order ASC, i.id ASC;

SELECT 'Q4b OPTIMIZED thumbnails: ONE statement for the whole page, idx_product_image_product_sort' AS query;
EXPLAIN ANALYZE
SELECT i.product_id, i.url, i.id
FROM product_image i
WHERE i.product_id IN (100000, 100008, 100016, 100024, 100032, 100040, 100048, 100056, 100064, 100072,
                       100080, 100088, 100096, 100104, 100112, 100120, 100128, 100136, 100144, 100152)
  AND i.sort_order = 0;

SELECT 'Q5a BASELINE category of each product: one SELECT per distinct category (lazy proxy)' AS query;
EXPLAIN SELECT c.id, c.name, c.slug, c.description FROM category c WHERE c.id = 3;

SELECT 'Q5b OPTIMIZED list page: category joined into the page query (entity graph)' AS query;
EXPLAIN ANALYZE
SELECT p.id, p.name, p.price, c.id, c.name
FROM product p
         LEFT OUTER JOIN category c ON c.id = p.category_id
WHERE p.active = TRUE AND p.category_id = 3
ORDER BY p.name ASC, p.id ASC
LIMIT 40, 20;

SELECT 'Q6 OPTIMIZED detail: product + category + images in one statement' AS query;
EXPLAIN ANALYZE
SELECT p.id, p.name, c.name, i.id, i.url
FROM product p
         INNER JOIN category c ON c.id = p.category_id
         LEFT OUTER JOIN product_image i ON i.product_id = p.id
WHERE p.id = 100123
ORDER BY i.sort_order ASC, i.id ASC;

SELECT 'Index inventory' AS query;
SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns, index_type
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name IN ('product', 'product_image', 'product_attribute')
GROUP BY table_name, index_name, index_type
ORDER BY table_name, index_name;
