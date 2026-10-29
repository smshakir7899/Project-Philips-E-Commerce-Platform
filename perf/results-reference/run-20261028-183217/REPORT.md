# product-service performance report

Generated 2026-10-28 13:04 UTC by `perf/report.py` from the raw JSON files in `/Users/rjsins/Documents/Phillips/philips-ecommerce/perf/results/20261028-183217`. **All numbers are measured**, none are estimated; missing scenarios are marked NOT RUN.

## Verdict

Claim under test: *"Improved performance by 30% through Redis caching, MySQL query optimization, database indexing, and efficient JPA/Hibernate data access."*

* Measured overall mean-latency improvement S1 -> S5: **90.9%** (p95: 93.0%) -> the 30% claim is **SUPPORTED** by this run on this machine.
* Without any cache (S1 -> S3, indexing + query optimization only): 76.2%.
* Quote the number together with the dataset size, concurrency and hardware below; a different machine will produce different absolute values.

## Scenarios (overall)

| Scenario | Setup | Requests | Errors | Throughput (req/s) | Mean (ms) | p50 | p90 | p95 | p99 | Max | Statements/req | Cache hit ratio | MySQL Questions/req | InnoDB rows read/req |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S1 | baseline code, V2 indexes DROPPED, no cache | 2000 | 0 | 108.0 | 146.43 | 89.94 | 362.53 | 396.74 | 489.40 | 682.64 | 17.59 | n/a | 20.68 | 28732.1 |
| S2 | baseline code, V2 indexes, no cache | 2000 | 0 | 88.3 | 179.24 | 77.96 | 507.21 | 613.40 | 775.39 | 1074.40 | 17.59 | n/a | 20.69 | 17096.2 |
| S3 | optimized code, V2 indexes, cache disabled | 2000 | 0 | 456.4 | 34.84 | 24.81 | 73.43 | 96.46 | 145.59 | 189.88 | 2.94 | n/a | 7.96 | 2073.4 |
| S4 | optimized code, V2 indexes, cache COLD (flushed, no warmup) | 2000 | 0 | 977.7 | 16.30 | 10.96 | 24.27 | 33.34 | 72.93 | 332.97 | 0.64 | 0.7180 | 2.08 | 191.1 |
| S5 | optimized code, V2 indexes, cache WARM (warmup pass first) | 2000 | 0 | 1193.3 | 13.32 | 11.24 | 21.77 | 27.61 | 44.59 | 72.70 | 0.40 | 0.8075 | 1.37 | 23.0 |

## Latency by request type

| Scenario | list mean / p95 (ms) | category_price mean / p95 (ms) | search mean / p95 (ms) | detail mean / p95 (ms) |
|---|---|---|---|---|
| S1 | 96.53 / 178.83 | 95.22 / 161.15 | 367.73 / 479.66 | 57.88 / 134.88 |
| S2 | 81.31 / 214.18 | 73.12 / 190.13 | 532.94 / 768.70 | 73.36 / 207.45 |
| S3 | 26.61 / 51.17 | 24.51 / 46.08 | 74.64 / 142.68 | 18.94 / 41.39 |
| S4 | 15.48 / 32.73 | 16.86 / 37.61 | 15.80 / 33.78 | 17.13 / 31.71 |
| S5 | 11.51 / 20.33 | 13.57 / 28.72 | 11.79 / 22.86 | 16.20 / 34.52 |

## Improvements

Formulas: latency improvement % = (before - after) / before x 100; throughput gain % = (after - before) / before x 100; statements/request = Hibernate prepareStatementCount / requests; cache hit ratio = hits / (hits + misses). Percentiles: nearest-rank.

| Factor | From -> To | Mean latency improvement | p50 improvement | p95 improvement | Throughput gain | Statements/req (from -> to) |
|---|---|---|---|---|---|---|
| Indexing | S1 -> S2 | -22.4% | 13.3% | -54.6% | -18.2% | 17.59 -> 17.59 |
| Query optimization (JPA/SQL) | S2 -> S3 | 80.6% | 68.2% | 84.3% | 417.0% | 17.59 -> 2.94 |
| Redis caching (warm) | S3 -> S5 | 61.8% | 54.7% | 71.4% | 161.5% | 2.94 -> 0.40 |
| Cold -> warm cache | S4 -> S5 | 18.3% | -2.6% | 17.2% | 22.1% | 0.64 -> 0.40 |
| OVERALL | S1 -> S5 | 90.9% | 87.5% | 93.0% | 1005.3% | 17.59 -> 0.40 |

### Mean-latency improvement per request type

| Request type | Indexing (S1->S2) | Query optimization (JPA/SQL) (S2->S3) | Redis caching (warm) (S3->S5) | Cold -> warm cache (S4->S5) | OVERALL (S1->S5) |
|---|---|---|---|---|---|
| list | 15.8% | 67.3% | 56.7% | 25.6% | 88.1% |
| category_price | 23.2% | 66.5% | 44.6% | 19.5% | 85.7% |
| search | -44.9% | 86.0% | 84.2% | 25.4% | 96.8% |
| detail | -26.7% | 74.2% | 14.5% | 5.4% | 72.0% |

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
| scenario start times | S1 2026-10-28T13:02:34.432113+00:00, S2 2026-10-28T13:03:18.663344+00:00, S3 2026-10-28T13:03:56.719276+00:00, S4 2026-10-28T13:04:11.164218+00:00, S5 2026-10-28T13:04:24.319049+00:00 |

## Caveats

* Load generator, service, MySQL and Redis share one machine: they compete for CPU, so absolute numbers are lower than on dedicated hosts and relative gains can differ.
* MySQL global status counters include any other activity on the server (other services' pollers).
* S4 also includes JIT warm-up of a freshly started JVM (it is the 'just deployed' worst case).
* The search semantics differ (LIKE substring vs FULLTEXT word prefix, see the README); result counts for the benchmark words are comparable but not identical.
* Re-run at least 3 times and report the median run if the numbers are used externally.
