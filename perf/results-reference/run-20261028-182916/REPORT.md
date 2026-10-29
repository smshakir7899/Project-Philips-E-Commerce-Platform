# product-service performance report

Generated 2026-10-28 13:01 UTC by `perf/report.py` from the raw JSON files in `/Users/rjsins/Documents/Phillips/philips-ecommerce/perf/results/20261028-182916`. **All numbers are measured**, none are estimated; missing scenarios are marked NOT RUN.

## Verdict

Claim under test: *"Improved performance by 30% through Redis caching, MySQL query optimization, database indexing, and efficient JPA/Hibernate data access."*

* Measured overall mean-latency improvement S1 -> S5: **91.0%** (p95: 93.4%) -> the 30% claim is **SUPPORTED** by this run on this machine.
* Without any cache (S1 -> S3, indexing + query optimization only): 75.4%.
* Quote the number together with the dataset size, concurrency and hardware below; a different machine will produce different absolute values.

## Scenarios (overall)

| Scenario | Setup | Requests | Errors | Throughput (req/s) | Mean (ms) | p50 | p90 | p95 | p99 | Max | Statements/req | Cache hit ratio | MySQL Questions/req | InnoDB rows read/req |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S1 | baseline code, V2 indexes DROPPED, no cache | 2000 | 0 | 103.5 | 153.05 | 96.82 | 375.18 | 415.69 | 503.68 | 716.95 | 17.59 | n/a | 20.68 | 28732.1 |
| S2 | baseline code, V2 indexes, no cache | 2000 | 0 | 89.9 | 175.98 | 73.28 | 503.25 | 624.95 | 750.33 | 989.96 | 17.59 | n/a | 20.69 | 17096.2 |
| S3 | optimized code, V2 indexes, cache disabled | 2000 | 0 | 421.4 | 37.64 | 26.96 | 79.16 | 98.64 | 146.41 | 242.50 | 2.94 | n/a | 7.96 | 2073.4 |
| S4 | optimized code, V2 indexes, cache COLD (flushed, no warmup) | 2000 | 0 | 609.5 | 26.13 | 14.74 | 38.51 | 65.33 | 161.48 | 699.73 | 0.64 | 0.7170 | 2.09 | 194.4 |
| S5 | optimized code, V2 indexes, cache WARM (warmup pass first) | 2000 | 0 | 1159.5 | 13.72 | 11.31 | 22.72 | 27.29 | 41.57 | 134.85 | 0.40 | 0.8065 | 1.38 | 23.0 |

## Latency by request type

| Scenario | list mean / p95 (ms) | category_price mean / p95 (ms) | search mean / p95 (ms) | detail mean / p95 (ms) |
|---|---|---|---|---|
| S1 | 103.83 / 183.12 | 100.55 / 173.86 | 380.29 / 493.87 | 60.04 / 132.18 |
| S2 | 77.26 / 211.93 | 73.33 / 198.60 | 525.77 / 745.16 | 71.54 / 208.61 |
| S3 | 28.93 / 56.99 | 27.79 / 51.27 | 79.28 / 144.27 | 20.43 / 43.75 |
| S4 | 25.93 / 65.64 | 26.07 / 69.79 | 25.57 / 66.43 | 26.82 / 55.39 |
| S5 | 12.24 / 22.72 | 13.41 / 28.07 | 12.06 / 22.05 | 16.75 / 30.96 |

## Improvements

Formulas: latency improvement % = (before - after) / before x 100; throughput gain % = (after - before) / before x 100; statements/request = Hibernate prepareStatementCount / requests; cache hit ratio = hits / (hits + misses). Percentiles: nearest-rank.

| Factor | From -> To | Mean latency improvement | p50 improvement | p95 improvement | Throughput gain | Statements/req (from -> to) |
|---|---|---|---|---|---|---|
| Indexing | S1 -> S2 | -15.0% | 24.3% | -50.3% | -13.1% | 17.59 -> 17.59 |
| Query optimization (JPA/SQL) | S2 -> S3 | 78.6% | 63.2% | 84.2% | 368.6% | 17.59 -> 2.94 |
| Redis caching (warm) | S3 -> S5 | 63.6% | 58.0% | 72.3% | 175.2% | 2.94 -> 0.40 |
| Cold -> warm cache | S4 -> S5 | 47.5% | 23.2% | 58.2% | 90.2% | 0.64 -> 0.40 |
| OVERALL | S1 -> S5 | 91.0% | 88.3% | 93.4% | 1020.0% | 17.59 -> 0.40 |

### Mean-latency improvement per request type

| Request type | Indexing (S1->S2) | Query optimization (JPA/SQL) (S2->S3) | Redis caching (warm) (S3->S5) | Cold -> warm cache (S4->S5) | OVERALL (S1->S5) |
|---|---|---|---|---|---|
| list | 25.6% | 62.6% | 57.7% | 52.8% | 88.2% |
| category_price | 27.1% | 62.1% | 51.7% | 48.6% | 86.7% |
| search | -38.3% | 84.9% | 84.8% | 52.8% | 96.8% |
| detail | -19.2% | 71.4% | 18.0% | 37.6% | 72.1% |

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
| scenario start times | S1 2026-10-28T12:59:41.876641+00:00, S2 2026-10-28T13:00:26.719376+00:00, S3 2026-10-28T13:01:05.358464+00:00, S4 2026-10-28T13:01:23.861555+00:00, S5 2026-10-28T13:01:43.305526+00:00 |

## Caveats

* Load generator, service, MySQL and Redis share one machine: they compete for CPU, so absolute numbers are lower than on dedicated hosts and relative gains can differ.
* MySQL global status counters include any other activity on the server (other services' pollers).
* S4 also includes JIT warm-up of a freshly started JVM (it is the 'just deployed' worst case).
* The search semantics differ (LIKE substring vs FULLTEXT word prefix, see the README); result counts for the benchmark words are comparable but not identical.
* Re-run at least 3 times and report the median run if the numbers are used externally.
