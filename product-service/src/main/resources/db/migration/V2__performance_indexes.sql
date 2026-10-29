-- =====================================================================================================
-- V2: indexes for the catalog READ workload (the "database indexing" part of the performance experiment).
-- perf/sql/drop-indexes.sql and perf/sql/create-indexes.sql are the exact inverse / replay of this file.
--
-- General notes:
--   * InnoDB secondary indexes implicitly end with the primary key, so (active, name) is physically
--     (active, name, id). That is why the application always adds "id" as the last ORDER BY column (stable
--     pagination): ORDER BY name, id can still be read in index order without a filesort.
--   * Column order in a composite index = equality columns first, then the range / ORDER BY column.
--   * Every index costs write amplification and buffer-pool memory; the catalog is read-heavy
--     (orders of magnitude more reads than writes), so this trade-off is acceptable here.
-- =====================================================================================================

-- Q: GET /api/products?categoryId=3&minPrice=100&maxPrice=200&sort=price,asc
--    WHERE category_id = ? AND active = 1 AND price BETWEEN ? AND ? ORDER BY price, id LIMIT ?
--    Two equality columns + a range on price: the range is resolved inside the index and rows come back
--    already sorted by price (no filesort). Without it MySQL uses idx_product_category, reads every product
--    of the category and sorts them.
CREATE INDEX idx_product_cat_active_price ON product (category_id, active, price);

-- Q: GET /api/products?categoryId=3&sort=name,asc   (the default sort of the catalog page)
--    WHERE category_id = ? AND active = 1 ORDER BY name, id LIMIT ?, ?
--    Equality on (category_id, active) then the index is already ordered by name -> MySQL reads only
--    OFFSET + LIMIT entries instead of filesorting the whole category.
CREATE INDEX idx_product_cat_active_name ON product (category_id, active, name);

-- Q: GET /api/products?sort=name,asc   (no category filter)  and the short-term prefix search
--    WHERE active = 1 ORDER BY name, id LIMIT ?   /   WHERE active = 1 AND name LIKE 'abc%'
CREATE INDEX idx_product_active_name ON product (active, name);

-- Q: GET /api/products?sort=createdAt,desc   ("newest products")
--    WHERE active = 1 ORDER BY created_at DESC, id DESC LIMIT ?  -> backward index scan, no filesort.
CREATE INDEX idx_product_active_created ON product (active, created_at);

-- Q: GET /api/products?q=airfryer   (optimized search)
--    WHERE MATCH(name, brand, description) AGAINST ('+airfryer*' IN BOOLEAN MODE)
--    An inverted word index. A B-tree cannot serve LIKE '%airfryer%' (leading wildcard) at all, so the
--    baseline search scans every row. Adding the first FULLTEXT index rebuilds the table once to add the
--    hidden FTS_DOC_ID column (expected warning). Default innodb_ft_min_token_size = 3: shorter words are not
--    indexed, the application falls back to a name-prefix search for them.
CREATE FULLTEXT INDEX ft_product_search ON product (name, brand, description);

-- Q: thumbnails of one result page (optimized list path, ONE query instead of N):
--    WHERE product_id IN (?, ?, ...) AND sort_order = 0
--    and ordered image loading in the detail page: WHERE product_id = ? ORDER BY sort_order, id
CREATE INDEX idx_product_image_product_sort ON product_image (product_id, sort_order);
