# Performance Results: Product Catalog API (measured, not estimated)

**Claim under test:** *"Improved application performance by 30% through Redis caching, MySQL query optimization,
database indexing, and efficient JPA/Hibernate data access."*

**Verdict for this lab setup:** the optimized implementation cut mean latency by **90.9%** (median of 3 runs) and
raised throughput about 11× compared with the baseline. Most of the gain comes from query/JPA optimization and Redis
caching. **Adding indexes without changing the queries made things worse** (see section 4). These numbers come
from one laptop, and the baseline is deliberately naive. In an interview, quote the method and how the
contributions break down, not just one percentage.

## 1. How it was measured

| Item | Value |
|---|---|
| Date | 2026-10-28, three consecutive runs (raw data: `perf/results-reference/run-*/S*.json`, one `REPORT.md` per run) |
| Machine | Apple Silicon MacBook Air, 10 CPUs, Docker Desktop (8 GB VM), Docker 29.8 |
| Everything on one host | load generator, product-service (Java 8 container, `-Xmx256m`, 400 MB limit), MySQL 8.0, Redis 7 |
| Dataset | 50,000 benchmark products (plus 20 demo products), about 3 images and 4 attributes each, 8 categories (`perf/sql/seed-benchmark-data.sql`) |
| Workload | 2,000 measured requests, concurrency 16, fixed seed 42: 30% list page, 20% category + price range sorted by price, 20% full-text search, 30% product detail (500 "hot" ids) |
| Warmup | 500 requests before measuring (except S4, which measures a cold cache on a fresh JVM) |
| Tooling | `perf/run-benchmark.sh` → `perf/bench.py` (stdlib Python, nearest-rank percentiles) → `perf/report.py` |
| Query counts | Hibernate `Statistics.prepareStatementCount` via `GET /api/products/admin/perf/stats`, reset before each run |
| DB load | MySQL `SHOW GLOBAL STATUS` deltas (`Questions`, `Innodb_rows_read`) |

Reproduce: `scripts/start.sh && perf/run-benchmark.sh && perf/run-benchmark.sh --skip-seed && perf/run-benchmark.sh --skip-seed`.

## 2. Results (median of 3 runs; individual runs in parentheses)

Each metric's median is taken independently across the three runs.

| Scenario | Setup | Throughput req/s | Mean ms | p50 | p95 | p99 | SQL statements / request | Cache hit ratio |
|---|---|---|---|---|---|---|---|---|
| S1 | baseline code, V2 indexes dropped, no cache | **108.0** (103.5, 108.0, 122.1) | **146.4** (153.1, 146.4, 129.8) | 89.9 | 396.7 | 489.4 | 17.59 | n/a |
| S2 | baseline code + indexes | **89.9** (89.9, 88.3, 108.7) | **176.0** (176.0, 179.2, 145.8) | 73.3 | 613.4 | 750.3 | 17.59 | n/a |
| S3 | optimized JPA/SQL + indexes, cache off | **442.6** (421.4, 456.4, 442.6) | **35.9** (37.6, 34.8, 35.9) | 27.0 | 96.5 | 145.6 | 2.94 | n/a |
| S4 | + Redis, cold (flushed, fresh JVM) | **977.7** (609.5, 977.7, 1026.6) | **16.3** (26.1, 16.3, 15.5) | 11.0 | 33.3 | 72.9 | 0.64 | 0.717 |
| S5 | + Redis, warm | **1193.3** (1159.5, 1193.3, 1771.8) | **13.3** (13.7, 13.3, 9.0) | 11.2 | 27.3 | 41.6 | 0.40 | 0.807 |

Errors: 0 in all 30,000 measured requests.

## 3. Contribution of each technique (computed from the medians)

Formulas: latency improvement = (before − after) / before × 100. Throughput gain = (after − before) / before × 100.

| Factor | From → To | Mean latency | p50 | p95 | Throughput |
|---|---|---|---|---|---|
| Indexing only (queries unchanged) | S1 → S2 | **−20.2%** (worse) | +18.5% | −54.6% (worse) | −16.7% |
| Query / JPA optimization | S2 → S3 | 79.6% | 63.2% | 84.3% | +392% |
| Indexing + query optimization (no cache) | S1 → S3 | **75.5%** | 70.0% | 75.7% | +310% |
| Redis cache-aside (warm) | S3 → S5 | 62.9% | 58.3% | 71.7% | +170% |
| Cold vs warm cache | S4 → S5 | 18.3% | −2.6% | 18.2% | +22% |
| **Overall** | S1 → S5 | **90.9%** | 87.5% | 93.1% | +1005% |

Rows read per request (run 1): S1 28,732 → S2 17,096 → S3 2,073 → S5 23. SQL statements per request: 17.6 → 2.9 → 0.4.

## 4. Why indexing alone made it slower (an interview-worthy finding)

This was consistent across all three runs. Per request type (run 1), indexes **helped** list and category queries
(about −26% mean latency each) but **hurt** search (+38%) and therefore the overall mean and p95. `EXPLAIN ANALYZE`
(`perf/sql/explain-queries.sql`, Q3a) shows why:

```
WITH indexes (count query of the baseline LIKE search):
-> Aggregate: count(p.id)  (actual time=57.5 ms)
   -> Filter: lower(name) like '%espresso%' or lower(description) like '%espresso%'  (rows=6002)
      -> Index lookup on p using idx_product_active_name (active=true)  (rows=49019)
WITHOUT indexes:
-> Aggregate: count(p.id)  (actual time=45.5 ms)
   -> Filter: active = true and (...)  (rows=6002)
      -> Table scan on p  (rows=50020)
```

* `LOWER(col) LIKE '%term%'` cannot use any B-tree index, because of the leading wildcard and the function on the column.
* With `idx_product_active_name` present, the optimizer chooses "index lookup on `active = true`". But `active` is
  true for about 98% of rows, so this means 49,019 secondary-index entries, each followed by a **random primary-key
  lookup** to read `name` and `description`. That costs more than one sequential table scan.
* The baseline runs this count on **every** search page (Spring Data `Page` → `COUNT` query), so search gets slower.
  Under 16 concurrent clients, those slow searches also hold connections and CPU that the other requests need.
* Lesson: an index only helps queries that can use it. Indexes on low-selectivity leading columns can mislead the
  optimizer, and every index also costs write time. The real gain came from **rewriting the queries** (FULLTEXT
  `MATCH ... AGAINST`, an entity graph instead of N+1, ID-first pagination) so they could use the right index.

## 5. What exactly differs between the baseline and the optimized code

| Concern | Baseline (`BaselineProductQueryService`) | Optimized (`OptimizedProductQueryService` + `OptimizedProductReader`) |
|---|---|---|
| List page | entities only; mapping touches lazy `category` and `images` → 1 + N + N queries | `@EntityGraph(category)` + one query for the thumbnails of the page ids |
| Search | `LOWER(name/description) LIKE '%q%'` → full scan | native FULLTEXT `MATCH(name, brand, description) AGAINST (... IN BOOLEAN MODE)`, IDs first, then hydrate |
| Detail | `findById` + lazy loads | fetch join category + images, `@BatchSize` attributes |
| Transactions | read-write (dirty checking, flush) | `@Transactional(readOnly = true)` (no flush, no snapshot copies) |
| Cache | none | Redis cache-aside, TTL 600 s detail / 120 s lists, invalidation after commit |
| Indexes | FK indexes only (S1) / V2 indexes (S2) | V2: `(category_id, active, price)`, `(category_id, active, name)`, `(active, name)`, `(active, created_at)`, FULLTEXT `(name, brand, description)`, `product_image (product_id, sort_order)` |

## 6. Honest caveats

* **The baseline is deliberately naive** (N+1, leading-wildcard LIKE), so the measured 91% is far above the resume's
  30%. Against a decent real-world baseline, gains are typically smaller. The defensible statement is: *"in a
  controlled benchmark of the catalogue API I measured X% lower mean latency, mostly from fixing N+1 and the search
  query, plus Redis cache-aside"*.
* All components shared one laptop, so absolute numbers are not production numbers. The variation between runs is
  visible above (for example S5 throughput 1,159 to 1,772 req/s).
* The search semantics differ: substring LIKE versus FULLTEXT word or prefix match.
* The warm hit ratio is about 0.81, not 1.0, because list pages draw on many parameter combinations and detail ids
  include a cold tail beyond the 500 hot ids.
* `S4` includes JIT warm-up of a freshly started JVM (the "just deployed" case).
