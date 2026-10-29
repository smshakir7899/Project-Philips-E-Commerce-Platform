# product-service performance report (TEMPLATE, NOT RUN)

No benchmark has been run yet. Every value below is **NOT RUN**. A real report is generated as
`perf/results/<timestamp>/REPORT.md` by:

```bash
docker compose up -d mysql redis config-server discovery-server product-service
perf/run-benchmark.sh                     # defaults: --rows 50000 --requests 2000 --concurrency 16 --warmup 500 --seed 42
# or regenerate the report for an existing results directory:
python3 perf/report.py perf/results/<timestamp>
```

## Verdict

Claim under test: *"Improved performance by 30% through Redis caching, MySQL query optimization, database
indexing, and efficient JPA/Hibernate data access."*

* Measured overall mean-latency improvement S1 -> S5: **NOT RUN**
* Without any cache (S1 -> S3): **NOT RUN**

## Scenarios (overall)

| Scenario | Setup | Requests | Errors | Throughput (req/s) | Mean (ms) | p50 | p90 | p95 | p99 | Max | Statements/req | Cache hit ratio | MySQL Questions/req | InnoDB rows read/req |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S1 | baseline code, V2 indexes DROPPED, no cache | NOT RUN | | | | | | | | | | | | |
| S2 | baseline code, V2 indexes, no cache | NOT RUN | | | | | | | | | | | | |
| S3 | optimized code, V2 indexes, cache disabled | NOT RUN | | | | | | | | | | | | |
| S4 | optimized code, V2 indexes, cache COLD (flushed, no warmup) | NOT RUN | | | | | | | | | | | | |
| S5 | optimized code, V2 indexes, cache WARM (warmup pass first) | NOT RUN | | | | | | | | | | | | |

## Latency by request type

| Scenario | list mean / p95 (ms) | category_price mean / p95 (ms) | search mean / p95 (ms) | detail mean / p95 (ms) |
|---|---|---|---|---|
| S1 | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| S2 | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| S3 | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| S4 | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| S5 | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

## Improvements

Formulas: latency improvement % = (before - after) / before x 100; throughput gain % = (after - before) / before x 100;
statements/request = Hibernate prepareStatementCount / requests; cache hit ratio = hits / (hits + misses).
Percentiles: nearest-rank.

| Factor | From -> To | Mean latency improvement | p50 improvement | p95 improvement | Throughput gain | Statements/req (from -> to) |
|---|---|---|---|---|---|---|
| Indexing | S1 -> S2 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Query optimization (JPA/SQL) | S2 -> S3 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Redis caching (warm) | S3 -> S5 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Cold -> warm cache | S4 -> S5 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| OVERALL | S1 -> S5 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

## Expected statement counts (verified by tests, not by a benchmark)

These are deterministic properties of the code. `ProductQueryStatementCountTest` and `ProductCatalogIT` assert them.
They are not latency results.

| Request | Baseline | Optimized (cache miss) | Optimized (cache hit) |
|---|---|---|---|
| list page, N products, C categories | 2 + C + N | 3 | 0 |
| full-text search page | 2 + C + N (full scan) | 4 | 0 |
| detail | 4 | 2 | 0 |
| batch of K ids | 1 + C + K | 2 | not cached |

## Environment and workload

| Item | Value |
|---|---|
| uname / CPUs / Docker version | NOT RUN (captured automatically by bench.py) |
| dataset | 50,000 benchmark products + 20 demo products |
| requests / concurrency / warmup / seed | 2000 / 16 / 500 / 42 |
| mix | list=30, category_price=20, search=20, detail=30 |
