-- Demo stock for the 20 sample products of product-service (ids and SKUs from docs/seed-products.csv).
-- Product 5 is deliberately LOW (2 units)  -> ordering 3 demonstrates 409 INSUFFICIENT_STOCK.
-- Product 7 is deliberately OUT OF STOCK   -> availability false, inStock false.
-- Product 20 is inactive in product-service (discontinued); it keeps a stock row for order history.
INSERT INTO inventory (product_id, sku, available_quantity, reserved_quantity, low_stock_threshold, version, updated_at) VALUES
    (1,  'PH-SONIC-9900',      120, 0, 10, 0, '2024-01-01 09:00:00.000000'),
    (2,  'PH-SONIC-4300',      200, 0, 10, 0, '2024-01-01 09:00:00.000000'),
    (3,  'PH-SONIC-FLOSS3000',  80, 0, 5,  0, '2024-01-01 09:00:00.000000'),
    (4,  'PH-HUE-E27-START3',  150, 0, 10, 0, '2024-01-01 09:00:00.000000'),
    (5,  'PH-HUE-PLAYBAR2',      2, 0, 5,  0, '2024-01-01 09:00:00.000000'),
    (6,  'PH-HUE-GRAD55',       60, 0, 5,  0, '2024-01-01 09:00:00.000000'),
    (7,  'PH-AIRF-HD9870',       0, 0, 5,  0, '2024-01-01 09:00:00.000000'),
    (8,  'PH-AIRF-HD9252',     180, 0, 10, 0, '2024-01-01 09:00:00.000000'),
    (9,  'PH-BLEND-HR3760',     90, 0, 5,  0, '2024-01-01 09:00:00.000000'),
    (10, 'PH-COFF-EP3246',      70, 0, 5,  0, '2024-01-01 09:00:00.000000'),
    (11, 'PH-COFF-EP5447',      50, 0, 5,  0, '2024-01-01 09:00:00.000000'),
    (12, 'PH-SENSEO-HD6553',   160, 0, 10, 0, '2024-01-01 09:00:00.000000'),
    (13, 'PH-ONEBLADE-QP6550', 140, 0, 10, 0, '2024-01-01 09:00:00.000000'),
    (14, 'PH-SHAVER-SP9883',    75, 0, 5,  0, '2024-01-01 09:00:00.000000'),
    (15, 'PH-TRIM-MG7920',     110, 0, 10, 0, '2024-01-01 09:00:00.000000'),
    (16, 'PH-AIRPUR-AC0850',    95, 0, 5,  0, '2024-01-01 09:00:00.000000'),
    (17, 'PH-AIRPUR-AC3033',    55, 0, 5,  0, '2024-01-01 09:00:00.000000'),
    (18, 'PH-HAIR-BHD827',     130, 0, 10, 0, '2024-01-01 09:00:00.000000'),
    (19, 'PH-HUE-ULTIMATE',     50, 0, 5,  0, '2024-01-01 09:00:00.000000'),
    (20, 'PH-AVENT-SCF293',     50, 0, 5,  0, '2024-01-01 09:00:00.000000');
