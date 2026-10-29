-- =====================================================================================================
-- Benchmark dataset for the product-service performance experiment (MySQL 8.0+).
--
--   * @bench_rows products (default 50,000) with ids 100000 .. 100000 + @bench_rows - 1,
--     SKU BENCH-0000000.., spread round-robin over the 8 categories of V3__sample_data.sql,
--   * 3 images per product (sort_order 0 = thumbnail, 1, 2) and 4 attributes per product,
--   * names / brands / descriptions built from realistic Philips vocabulary so that the search words used
--     by perf/bench.py (toothbrush, airfryer, espresso, shaver, purifier, ...) have realistic selectivity,
--   * prices 19.99 .. 1019.98 spread pseudo-randomly, created_at spread over ~3 years.
--
-- IDEMPOTENT: previous benchmark rows (id >= 100000) are deleted first; the 20 demo products are untouched.
-- Usage (run-benchmark.sh does this):
--   { echo "SET @bench_rows = 50000;"; cat perf/sql/seed-benchmark-data.sql; } \
--     | docker compose exec -T -e MYSQL_PWD=root_pwd mysql mysql -uroot philips_product
-- Generation uses recursive CTEs (no stored procedure, no client loop): one INSERT ... SELECT per table.
-- =====================================================================================================

SET @bench_rows = COALESCE(@bench_rows, 50000);
SET @bench_first_id = 100000;
-- a recursive CTE produces one row per recursion level; the default limit is 1000
SET SESSION cte_max_recursion_depth = 10000000;

-- children first (foreign keys)
DELETE FROM product_attribute WHERE product_id >= @bench_first_id;
DELETE FROM product_image WHERE product_id >= @bench_first_id;
DELETE FROM product WHERE id >= @bench_first_id;

INSERT INTO product (id, sku, name, description, brand, price, currency, category_id, active,
                     created_at, updated_at, version)
WITH RECURSIVE seq (n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @bench_rows - 1
)
SELECT @bench_first_id + n,
       CONCAT('BENCH-', LPAD(n, 7, '0')),
       -- e.g. "Philips Sonicare Series 7 Electric Toothbrush Pro 1234"
       CONCAT(ELT(1 + n % 8, 'Philips Sonicare', 'Philips Hue', 'Philips', 'Philips', 'Philips',
                  'Philips', 'Philips', 'Philips Avent'),
              ' ', ELT(1 + (n DIV 8) % 6, 'Series 1', 'Series 3', 'Series 5', 'Series 7', 'Series 9', 'Premium'),
              ' ', ELT(1 + n % 8,
                       ELT(1 + (n DIV 48) % 3, 'Electric Toothbrush', 'Power Flosser', 'Kids Toothbrush'),
                       ELT(1 + (n DIV 48) % 3, 'Smart Bulb', 'Lightstrip', 'Ceiling Lamp'),
                       ELT(1 + (n DIV 48) % 3, 'Airfryer', 'Blender', 'Kettle'),
                       ELT(1 + (n DIV 48) % 3, 'Espresso Machine', 'Coffee Pod Machine', 'Milk Frother'),
                       ELT(1 + (n DIV 48) % 3, 'Electric Shaver', 'OneBlade Trimmer', 'Multigroom Trimmer'),
                       ELT(1 + (n DIV 48) % 3, 'Air Purifier', 'Humidifier', 'Air Purifier Combi'),
                       ELT(1 + (n DIV 48) % 3, 'Hair Dryer', 'Hair Straightener', 'Hair Styler'),
                       ELT(1 + (n DIV 48) % 3, 'Baby Bottle Set', 'Bottle Steriliser', 'Baby Monitor')),
              ' ', ELT(1 + (n DIV 144) % 5, 'Pro', 'Plus', 'Essential', 'Deluxe', 'Compact'),
              ' ', n),
       CONCAT('Benchmark product ', n, '. ',
              ELT(1 + n % 8,
                  'Sonic technology removes plaque and keeps gums healthy with a gentle toothbrush experience.',
                  'Smart lighting with millions of colours, app and voice control for every room.',
                  'Kitchen appliance with Rapid Air technology for healthy and crispy cooking results.',
                  'Fully automatic espresso and coffee with a silky milk froth at the touch of a button.',
                  'Comfortable shaving and trimming with precise blades for face and body.',
                  'HEPA filtration removes allergens, pollen and dust for cleaner air at home.',
                  'Fast drying and styling with ionic care and gentle temperature control.',
                  'Designed for mother and child comfort with anti-colic bottles and safe sterilising.'),
              ' Warranty ', 1 + n % 3, ' years. Energy class ', ELT(1 + n % 4, 'A', 'B', 'C', 'D'), '.'),
       ELT(1 + n % 8, 'Philips Sonicare', 'Philips Hue', 'Philips', 'Philips', 'Philips', 'Philips', 'Philips',
           'Philips Avent'),
       -- (n * 7919) mod 100000 is a cheap deterministic permutation -> prices are not correlated with ids
       ROUND(19.99 + ((n * 7919) % 100000) / 100, 2),
       'EUR',
       1 + n % 8,
       -- ~2% inactive (discontinued) products, as in a real catalog
       IF(n % 50 = 49, FALSE, TRUE),
       TIMESTAMP('2023-01-01 00:00:00') + INTERVAL ((n * 104729) % 94608000) SECOND,
       TIMESTAMP('2023-01-01 00:00:00') + INTERVAL ((n * 104729) % 94608000) SECOND,
       0
FROM seq;

INSERT INTO product_image (product_id, s3_key, url, content_type, sort_order)
SELECT p.id,
       CONCAT('products/', p.id, '/bench-', s.k, '.jpg'),
       CONCAT('http://localhost:9000/philips-product-images/products/', p.id, '/bench-', s.k, '.jpg'),
       'image/jpeg',
       s.k
FROM product p
         CROSS JOIN (SELECT 0 AS k UNION ALL SELECT 1 UNION ALL SELECT 2) s
WHERE p.id >= @bench_first_id;

INSERT INTO product_attribute (product_id, attr_name, attr_value)
SELECT p.id,
       ELT(s.k, 'Colour', 'Weight', 'Power', 'Warranty'),
       CASE s.k
           WHEN 1 THEN ELT(1 + p.id % 4, 'White', 'Black', 'Silver', 'Blue')
           WHEN 2 THEN CONCAT(200 + p.id % 1800, ' g')
           WHEN 3 THEN CONCAT(5 + p.id % 2300, ' W')
           ELSE CONCAT(1 + p.id % 3, ' years')
           END
FROM product p
         CROSS JOIN (SELECT 1 AS k UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) s
WHERE p.id >= @bench_first_id;

-- Refresh optimizer statistics so EXPLAIN / plans reflect the new data distribution.
ANALYZE TABLE product, product_image, product_attribute;

SELECT COUNT(*) AS benchmark_products FROM product WHERE id >= @bench_first_id;
SELECT COUNT(*) AS benchmark_images FROM product_image WHERE product_id >= @bench_first_id;
SELECT COUNT(*) AS benchmark_attributes FROM product_attribute WHERE product_id >= @bench_first_id;
