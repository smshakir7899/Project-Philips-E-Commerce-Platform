# product-service performance experiment

Goal: turn the resume sentence

> Improved performance by 30% through Redis caching, MySQL query optimization, database indexing, and
> efficient JPA/Hibernate data access.

into a **measured, reproducible** result, or show that it does not hold. Nothing in this directory contains
results. Every number comes from a run on your machine, and `REPORT-TEMPLATE.md` stays "NOT RUN" until then.

## What is compared

Both read paths live in product-service and implement `ProductQueryService`. Exactly one bean is active, chosen by
`app.product.query-mode` (env `PRODUCT_QUERY_MODE`).

| Aspect | Baseline (`BaselineProductQueryService`) | Optimized (`OptimizedProductQueryService` + `OptimizedProductReader`) |
|---|---|---|
| Associations | entities only; lazy category + images touched per product (**N+1**) | `@EntityGraph(category)` on the page query + **one** thumbnail query per page |
| Statements, list page of 20 | `2 + C + 20` (C = distinct categories) | `3` (miss) / `0` (cache hit) |
| Statements, detail | `4` | `2` (fetch join category+images, attributes) / `0` (hit) |
| Search | `LOWER(name) LIKE '%q%' OR LOWER(description) LIKE '%q%'`, a full scan | `MATCH(name,brand,description) AGAINST('+q*' IN BOOLEAN MODE)` with `ft_product_search` |
| Filters | JPQL `(:x is null or ...)` | Specifications (only present predicates), shaped for `(category_id, active, price)` |
| Transactions | `@Transactional` (read-write, dirty checking at commit) | `@Transactional(readOnly = true)`, opened **only on a cache miss** |
| Cache | none | Redis cache-aside (`ProductCacheService`), detail TTL 600 s, list TTL 120 s |
| Indexes | V1 only (PK, unique, FK) in S1 | V1 + V2 (`V2__performance_indexes.sql`) |

The statement counts are asserted by `ProductQueryStatementCountTest` (H2) and `ProductCatalogIT` (MySQL), so they
are verified facts and not estimates.

**Search semantics differ** and this is intentional. That is what a FULLTEXT migration does in practice. LIKE
matches substrings anywhere ("fryer" matches "Airfryer"). FULLTEXT matches words and word prefixes ("airfry"
matches "Airfryer", "fryer" does not), skips stopwords and words shorter than 3 characters (those fall back to an
indexed `name LIKE 'q%'`), and also searches `brand`. The benchmark words are whole words, so both variants
return comparable result sets.

## Scenarios

| | Code | V2 indexes | Cache | Isolates |
|---|---|---|---|---|
| S1 | baseline | **dropped** | off | starting point |
| S2 | baseline | present | off | S1 -> S2 = **indexing** |
| S3 | optimized | present | disabled | S2 -> S3 = **query / JPA optimization** |
| S4 | optimized | present | **cold** (flushed, no warmup) | worst case right after deploy |
| S5 | optimized | present | **warm** (flush + warmup) | S3 -> S5 = **caching**; S1 -> S5 = **overall** |

Formulas, computed by `report.py` from the JSON files:

* latency improvement % = (before - after) / before x 100 (mean, p50, p95)
* throughput gain % = (after - before) / before x 100
* statements/request = Hibernate `prepareStatementCount` / measured requests
* cache hit ratio = hits / (hits + misses)
* percentiles use the nearest-rank method, `sorted[ceil(p/100 x N) - 1]`

## Fairness controls

* **Same dataset**: `sql/seed-benchmark-data.sql`, 50,000 products by default (ids 100000+), 3 images and 4
  attributes each, deterministic and idempotent.
* **Same requests**: `bench.py` builds the request list from `--seed` (default 42). Every scenario sends exactly
  the same requests in the same order. The warmup list uses `seed + 1` with the same distribution and the same
  "hot" product set (80% of detail requests hit 500 popular products, 20% are uniform), so a warm cache reflects
  popularity instead of a replay of the measured list.
* **Same concurrency, client and connection handling**: N worker threads, one keep-alive HTTP connection each.
* **Same JVM state**: every scenario recreates the product-service container and then sends the same number of
  warmup requests. The exception is S4, which has no warmup by definition. **JVM caveat**: S4 therefore also
  measures JIT compilation of a fresh JVM, so read it as the "just deployed" worst case and not as a pure
  cache-cold number.
* **Counters reset after warmup**: `POST /api/products/admin/perf/reset` runs right before the measured run, so the
  statement and cache counters cover only the measured requests.
* **Mode verification**: `run-benchmark.sh` asks `/api/products/admin/perf/stats` which bean is active and aborts
  on a mismatch.
* **Index restore**: a `trap` re-runs `sql/create-indexes.sql` even when the script fails.

## Caveats (state them when you quote numbers)

* **Single machine**: load generator, JVM, MySQL and Redis compete for the same CPUs, which compresses
  differences. Absolute numbers depend heavily on hardware. Quote relative numbers together with the
  environment table in the report.
* MySQL `SHOW GLOBAL STATUS` deltas count everything on the server. Stop other services (especially the outbox
  pollers of order-service and payment-service) for clean `Questions` and `Innodb_rows_read` numbers.
* The InnoDB buffer pool is warm for every scenario, because seeding and the previous scenarios already read
  the data. This favors the baseline, not the optimized path.
* One run is one sample. Run the whole suite at least 3 times and report the median run.
* The 30% figure is a hypothesis to test. If a run shows less, the report says "NOT supported". Do not change
  the workload until the number fits.

## Running with Docker Compose (recommended)

`docker-compose.yml` must pass these two variables through to product-service:
`PRODUCT_QUERY_MODE: ${PRODUCT_QUERY_MODE:-optimized}` and `PRODUCT_CACHE_ENABLED: ${PRODUCT_CACHE_ENABLED:-true}`.

```bash
docker compose up -d mysql redis config-server discovery-server product-service
perf/run-benchmark.sh                      # seed 50k rows, run S1..S5, write perf/results/<ts>/REPORT.md
perf/run-benchmark.sh --skip-seed --requests 5000 --concurrency 32
perf/run-benchmark.sh --scenarios "S3 S5"  # subset
MYSQL_ROOT_PASSWORD=secret perf/run-benchmark.sh
```

Manual pieces:

```bash
# dataset
{ echo "SET @bench_rows = 50000;"; cat perf/sql/seed-benchmark-data.sql; } \
  | docker compose exec -T -e MYSQL_PWD=root_pwd mysql mysql -uroot philips_product
# indexes off / on (idempotent)
docker compose exec -T -e MYSQL_PWD=root_pwd mysql mysql -uroot philips_product < perf/sql/drop-indexes.sql
docker compose exec -T -e MYSQL_PWD=root_pwd mysql mysql -uroot philips_product < perf/sql/create-indexes.sql
# query plans (run once with indexes dropped, once with indexes present)
docker compose exec -T -e MYSQL_PWD=root_pwd mysql mysql -uroot -t philips_product < perf/sql/explain-queries.sql
```

## Running against a locally started service (no Docker for the app)

You need MySQL 8 and Redis reachable locally, and config-server running, or run the jar with
`--spring.cloud.config.enabled=false` and pass the properties from `config-repo/product-service.yml` yourself.

```bash
mvn -f product-service/pom.xml -DskipTests package
mysql -uroot -p philips_product < perf/sql/seed-benchmark-data.sql      # after the app ran Flyway once

# one scenario = one service start with the wanted mode
PRODUCT_QUERY_MODE=baseline PRODUCT_CACHE_ENABLED=false java -jar product-service/target/product-service.jar &
python3 perf/bench.py --base-url http://localhost:8081 --scenario S2 --out perf/results/local/S2.json
# stop it, restart with PRODUCT_QUERY_MODE=optimized PRODUCT_CACHE_ENABLED=false -> S3, then cache enabled:
python3 perf/bench.py --scenario S4 --warmup 0 --flush-cache --out perf/results/local/S4.json
python3 perf/bench.py --scenario S5 --flush-cache --out perf/results/local/S5.json
python3 perf/report.py perf/results/local
```

Leave out `--mysql-docker` when MySQL does not run under Docker Compose. The MySQL status deltas are then omitted.
For S1, run `perf/sql/drop-indexes.sql` before starting the baseline service and `create-indexes.sql` afterwards.

## Files

| File | Purpose |
|---|---|
| `bench.py` | deterministic load generator plus counter capture, writes raw JSON (stdlib only) |
| `report.py` | builds REPORT.md (tables, formulas, environment, verdict) from a results directory |
| `run-benchmark.sh` | orchestrates seed, index switching, container recreation and S1..S5 |
| `sql/seed-benchmark-data.sql` | 50k-product dataset via recursive CTEs, idempotent |
| `sql/drop-indexes.sql` / `sql/create-indexes.sql` | exact inverse and replay of V2, safe to re-run |
| `sql/explain-queries.sql` | EXPLAIN / EXPLAIN ANALYZE of baseline vs optimized SQL |
| `REPORT-TEMPLATE.md` | the shape of the report, all values NOT RUN |
