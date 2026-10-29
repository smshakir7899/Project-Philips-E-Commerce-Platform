# product-service performance report

Generated 2026-10-28 13:06 UTC by `perf/report.py` from the raw JSON files in `/Users/rjsins/Documents/Phillips/philips-ecommerce/perf/results/20261028-183436`. **All numbers are measured**, none are estimated; missing scenarios are marked NOT RUN.

## Verdict

Claim under test: *"Improved performance by 30% through Redis caching, MySQL query optimization, database indexing, and efficient JPA/Hibernate data access."*

* Measured overall mean-latency improvement S1 -> S5: **93.1%** (p95: 95.0%) -> the 30% claim is **SUPPORTED** by this run on this machine.
* Without any cache (S1 -> S3, indexing + query optimization only): 72.4%.
* Quote the number together with the dataset size, concurrency and hardware below; a different machine will produce different absolute values.

## Scenarios (overall)

| Scenario | Setup | Requests | Errors | Throughput (req/s) | Mean (ms) | p50 | p90 | p95 | p99 | Max | Statements/req | Cache hit ratio | MySQL Questions/req | InnoDB rows read/req |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S1 | baseline code, V2 indexes DROPPED, no cache | 2000 | 0 | 122.1 | 129.76 | 80.72 | 329.07 | 351.99 | 421.20 | 611.68 | 17.59 | n/a | 20.66 | 28732.1 |
| S2 | baseline code, V2 indexes, no cache | 2000 | 0 | 108.7 | 145.77 | 64.46 | 420.60 | 503.93 | 615.50 | 949.21 | 17.59 | n/a | 20.67 | 17096.2 |
| S3 | optimized code, V2 indexes, cache disabled | 2000 | 0 | 442.6 | 35.87 | 27.07 | 75.09 | 92.59 | 144.05 | 258.55 | 2.94 | n/a | 7.96 | 2073.4 |
| S4 | optimized code, V2 indexes, cache COLD (flushed, no warmup) | 2000 | 0 | 1026.6 | 15.53 | 10.12 | 23.03 | 32.56 | 67.04 | 337.81 | 0.65 | 0.7160 | 2.10 | 194.3 |
| S5 | optimized code, V2 indexes, cache WARM (warmup pass first) | 2000 | 0 | 1771.8 | 8.96 | 7.72 | 15.00 | 17.51 | 22.84 | 32.89 | 0.40 | 0.8070 | 1.37 | 23.0 |

## Latency by request type

| Scenario | list mean / p95 (ms) | category_price mean / p95 (ms) | search mean / p95 (ms) | detail mean / p95 (ms) |
|---|---|---|---|---|
| S1 | 84.06 / 142.36 | 84.45 / 143.35 | 328.69 / 416.08 | 50.52 / 106.24 |
| S2 | 64.53 / 175.15 | 61.64 / 150.63 | 433.62 / 610.49 | 59.58 / 179.46 |
| S3 | 27.59 / 56.04 | 27.38 / 54.25 | 74.70 / 138.96 | 19.51 / 41.15 |
| S4 | 14.80 / 28.94 | 16.06 / 39.60 | 14.80 / 24.19 | 16.46 / 29.70 |
| S5 | 7.99 / 14.19 | 8.94 / 18.48 | 7.83 / 14.01 | 10.87 / 19.82 |

## Improvements

Formulas: latency improvement % = (before - after) / before x 100; throughput gain % = (after - before) / before x 100; statements/request = Hibernate prepareStatementCount / requests; cache hit ratio = hits / (hits + misses). Percentiles: nearest-rank.

| Factor | From -> To | Mean latency improvement | p50 improvement | p95 improvement | Throughput gain | Statements/req (from -> to) |
|---|---|---|---|---|---|---|
| Indexing | S1 -> S2 | -12.3% | 20.1% | -43.2% | -10.9% | 17.59 -> 17.59 |
| Query optimization (JPA/SQL) | S2 -> S3 | 75.4% | 58.0% | 81.6% | 307.0% | 17.59 -> 2.94 |
| Redis caching (warm) | S3 -> S5 | 75.0% | 71.5% | 81.1% | 300.3% | 2.94 -> 0.40 |
| Cold -> warm cache | S4 -> S5 | 42.3% | 23.7% | 46.2% | 72.6% | 0.65 -> 0.40 |
| OVERALL | S1 -> S5 | 93.1% | 90.4% | 95.0% | 1350.9% | 17.59 -> 0.40 |

### Mean-latency improvement per request type

| Request type | Indexing (S1->S2) | Query optimization (JPA/SQL) (S2->S3) | Redis caching (warm) (S3->S5) | Cold -> warm cache (S4->S5) | OVERALL (S1->S5) |
|---|---|---|---|---|---|
| list | 23.2% | 57.2% | 71.0% | 46.0% | 90.5% |
| category_price | 27.0% | 55.6% | 67.3% | 44.3% | 89.4% |
| search | -31.9% | 82.8% | 89.5% | 47.1% | 97.6% |
| detail | -17.9% | 67.2% | 44.3% | 34.0% | 78.5% |

## Environment and workload

| Item | Value |
|---|---|
| uname | Darwin Ravijots-MacBook-Air.local 27.0.0 Darwin Kernel Version 27.0.0: Tue Aug 11 21:02:59 PDT 2026; root:xnu-13432.1.9~1/RELEASE_ARM64_T8142 arm64 |
| cpuCount | 10 |
| python | 3.9.6 |
| dockerServerVersion | 29.8.2 |
| dockerComposeVersion | 5.6.0 |
| baseUrl | http://localhost:8081 |
| requests | 2000 |
| concurrency | 16 |
| warmup | 500 |
| seed | 42 |
| mix | {'list': 30.0, 'category_price': 20.0, 'search': 20.0, 'detail': 30.0} |
| idMin | 100000 |
| idMax | 149999 |
| hotIds | 500 |
| percentileMethod | nearest-rank |
| scenario start times | S1 2026-10-28T13:04:51.251956+00:00, S2 2026-10-28T13:05:26.350967+00:00, S3 2026-10-28T13:05:56.543522+00:00, S4 2026-10-28T13:06:11.177867+00:00, S5 2026-10-28T13:06:24.195363+00:00 |

## Caveats

* Load generator, service, MySQL and Redis share one machine: they compete for CPU, so absolute numbers are lower than on dedicated hosts and relative gains can differ.
* MySQL global status counters include any other activity on the server (other services' pollers).
* S4 also includes JIT warm-up of a freshly started JVM (it is the 'just deployed' worst case).
* The search semantics differ (LIKE substring vs FULLTEXT word prefix, see the README); result counts for the benchmark words are comparable but not identical.
* Re-run at least 3 times and report the median run if the numbers are used externally.
