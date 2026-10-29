#!/usr/bin/env python3
"""
Builds REPORT.md from the S*.json files written by bench.py (standard library only).

    python3 perf/report.py perf/results/20261028-153000

Every number in the report is read from the JSON files - nothing is estimated or hardcoded. A scenario whose
file is missing is shown as "NOT RUN" and every comparison that needs it is "n/a".

Formulas (B = before, A = after):
    latency improvement %  = (B - A) / B * 100        (mean, p50, p95; positive = faster)
    throughput gain %      = (A - B) / B * 100        (positive = more requests per second)
    statements / request   = Hibernate prepareStatementCount / measured requests
    cache hit ratio        = hits / (hits + misses)

Contribution isolation (each step changes ONE factor):
    indexing            S1 -> S2   (baseline code, indexes dropped -> indexes present)
    query optimization  S2 -> S3   (baseline code -> optimized JPA/SQL, both without cache)
    caching             S3 -> S5   (optimized without cache -> optimized with a warm cache)
    overall             S1 -> S5
    cold vs warm cache  S4 -> S5
"""
import glob
import json
import os
import sys
from datetime import datetime, timezone

SCENARIOS = [
    ("S1", "baseline code, V2 indexes DROPPED, no cache"),
    ("S2", "baseline code, V2 indexes, no cache"),
    ("S3", "optimized code, V2 indexes, cache disabled"),
    ("S4", "optimized code, V2 indexes, cache COLD (flushed, no warmup)"),
    ("S5", "optimized code, V2 indexes, cache WARM (warmup pass first)"),
]
COMPARISONS = [
    ("Indexing", "S1", "S2"),
    ("Query optimization (JPA/SQL)", "S2", "S3"),
    ("Redis caching (warm)", "S3", "S5"),
    ("Cold -> warm cache", "S4", "S5"),
    ("OVERALL", "S1", "S5"),
]
TYPES = ["list", "category_price", "search", "detail"]


def load(results_dir):
    data = {}
    for path in sorted(glob.glob(os.path.join(results_dir, "S*.json"))):
        with open(path) as handle:
            result = json.load(handle)
        data[result.get("scenario") or os.path.basename(path)[:-5]] = result
    return data


def fmt(value, digits=2, suffix=""):
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return ("%." + str(digits) + "f%s") % (value, suffix)
    return "%s%s" % (value, suffix)


def improvement(before, after):
    """(before - after) / before * 100 - for latencies (lower is better)."""
    if before is None or after is None or before == 0:
        return None
    return (before - after) / float(before) * 100.0


def gain(before, after):
    """(after - before) / before * 100 - for throughput (higher is better)."""
    if before is None or after is None or before == 0:
        return None
    return (after - before) / float(before) * 100.0


def overall(data, scenario, key):
    result = data.get(scenario)
    return None if result is None else result["overall"].get(key)


def derived(data, scenario, key):
    result = data.get(scenario)
    return None if result is None else (result.get("derived") or {}).get(key)


def scenario_table(data):
    lines = ["| Scenario | Setup | Requests | Errors | Throughput (req/s) | Mean (ms) | p50 | p90 | p95 | p99 | Max | "
             "Statements/req | Cache hit ratio | MySQL Questions/req | InnoDB rows read/req |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for name, setup in SCENARIOS:
        result = data.get(name)
        if result is None:
            lines.append("| %s | %s | NOT RUN |  |  |  |  |  |  |  |  |  |  |  |  |" % (name, setup))
            continue
        o = result["overall"]
        d = result.get("derived") or {}
        lines.append("| %s | %s | %d | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            name, setup, o["count"], o["errors"], fmt(o["throughputRps"], 1), fmt(o["meanMs"]), fmt(o["p50Ms"]),
            fmt(o["p90Ms"]), fmt(o["p95Ms"]), fmt(o["p99Ms"]), fmt(o["maxMs"]), fmt(d.get("statementsPerRequest")),
            fmt(d.get("cacheHitRatio"), 4), fmt(d.get("mysqlQuestionsPerRequest")),
            fmt(d.get("innodbRowsReadPerRequest"), 1)))
    return lines


def per_type_table(data):
    lines = ["| Scenario | " + " | ".join("%s mean / p95 (ms)" % t for t in TYPES) + " |",
             "|---|" + "---|" * len(TYPES)]
    for name, _ in SCENARIOS:
        result = data.get(name)
        if result is None:
            lines.append("| %s |" % name + " NOT RUN |" * len(TYPES))
            continue
        cells = []
        for kind in TYPES:
            stats = result["byType"].get(kind)
            cells.append("n/a" if not stats else "%s / %s" % (fmt(stats["meanMs"]), fmt(stats["p95Ms"])))
        lines.append("| %s | %s |" % (name, " | ".join(cells)))
    return lines


def comparison_table(data):
    lines = ["| Factor | From -> To | Mean latency improvement | p50 improvement | p95 improvement | "
             "Throughput gain | Statements/req (from -> to) |",
             "|---|---|---|---|---|---|---|"]
    for label, before, after in COMPARISONS:
        lines.append("| %s | %s -> %s | %s | %s | %s | %s | %s -> %s |" % (
            label, before, after,
            fmt(improvement(overall(data, before, "meanMs"), overall(data, after, "meanMs")), 1, "%"),
            fmt(improvement(overall(data, before, "p50Ms"), overall(data, after, "p50Ms")), 1, "%"),
            fmt(improvement(overall(data, before, "p95Ms"), overall(data, after, "p95Ms")), 1, "%"),
            fmt(gain(overall(data, before, "throughputRps"), overall(data, after, "throughputRps")), 1, "%"),
            fmt(derived(data, before, "statementsPerRequest")), fmt(derived(data, after, "statementsPerRequest"))))
    return lines


def per_type_comparison(data):
    lines = ["| Request type | " + " | ".join("%s (%s->%s)" % (c[0], c[1], c[2]) for c in COMPARISONS) + " |",
             "|---|" + "---|" * len(COMPARISONS)]
    for kind in TYPES:
        cells = []
        for _, before, after in COMPARISONS:
            b = data.get(before, {}).get("byType", {}).get(kind) if data.get(before) else None
            a = data.get(after, {}).get("byType", {}).get(kind) if data.get(after) else None
            cells.append(fmt(improvement(b and b.get("meanMs"), a and a.get("meanMs")), 1, "%"))
        lines.append("| %s | %s |" % (kind, " | ".join(cells)))
    return lines


def claim_check(data):
    mean_gain = improvement(overall(data, "S1", "meanMs"), overall(data, "S5", "meanMs"))
    p95_gain = improvement(overall(data, "S1", "p95Ms"), overall(data, "S5", "p95Ms"))
    no_cache = improvement(overall(data, "S1", "meanMs"), overall(data, "S3", "meanMs"))
    if mean_gain is None:
        return ["The resume claim (\"30% improvement\") cannot be evaluated: S1 and/or S5 were not run."]
    verdict = "SUPPORTED" if mean_gain >= 30.0 else "NOT supported"
    lines = ["Claim under test: *\"Improved performance by 30% through Redis caching, MySQL query optimization, "
             "database indexing, and efficient JPA/Hibernate data access.\"*", "",
             "* Measured overall mean-latency improvement S1 -> S5: **%s** (p95: %s) -> the 30%% claim is **%s** "
             "by this run on this machine." % (fmt(mean_gain, 1, "%"), fmt(p95_gain, 1, "%"), verdict),
             "* Without any cache (S1 -> S3, indexing + query optimization only): %s." % fmt(no_cache, 1, "%")]
    errors = sum(data[s]["overall"]["errors"] for s in data)
    if errors:
        lines.append("* WARNING: %d requests failed across all scenarios - check statusCounts in the JSON files "
                     "before quoting any number." % errors)
    lines.append("* Quote the number together with the dataset size, concurrency and hardware below; "
                 "a different machine will produce different absolute values.")
    return lines


def environment_section(data):
    any_result = next(iter(data.values()))
    env = any_result.get("environment") or {}
    cfg = any_result.get("config") or {}
    lines = ["| Item | Value |", "|---|---|"]
    for key in ("uname", "cpuCount", "python", "dockerServerVersion", "dockerComposeVersion"):
        lines.append("| %s | %s |" % (key, env.get(key) if env.get(key) is not None else "n/a"))
    for key in ("baseUrl", "requests", "concurrency", "warmup", "seed", "mix", "idMin", "idMax", "hotIds",
                "percentileMethod"):
        lines.append("| %s | %s |" % (key, cfg.get(key)))
    lines.append("| scenario start times | %s |" % ", ".join(
        "%s %s" % (name, data[name].get("startedAt")) for name, _ in SCENARIOS if name in data))
    return lines


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: report.py <results-dir>")
    results_dir = sys.argv[1]
    data = load(results_dir)
    if not data:
        raise SystemExit("no S*.json files in " + results_dir)

    out = ["# product-service performance report", "",
           "Generated %s by `perf/report.py` from the raw JSON files in `%s`. **All numbers are measured**, none "
           "are estimated; missing scenarios are marked NOT RUN." % (
               datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC"), results_dir), "",
           "## Verdict", ""] + claim_check(data) + [
        "", "## Scenarios (overall)", ""] + scenario_table(data) + [
        "", "## Latency by request type", ""] + per_type_table(data) + [
        "", "## Improvements", "",
        "Formulas: latency improvement % = (before - after) / before x 100; throughput gain % = "
        "(after - before) / before x 100; statements/request = Hibernate prepareStatementCount / requests; "
        "cache hit ratio = hits / (hits + misses). Percentiles: nearest-rank.", ""] + comparison_table(data) + [
        "", "### Mean-latency improvement per request type", ""] + per_type_comparison(data) + [
        "", "## Environment and workload", ""] + environment_section(data) + [
        "", "## Caveats", "",
        "* Load generator, service, MySQL and Redis share one machine: they compete for CPU, so absolute numbers "
        "are lower than on dedicated hosts and relative gains can differ.",
        "* MySQL global status counters include any other activity on the server (other services' pollers).",
        "* S4 also includes JIT warm-up of a freshly started JVM (it is the 'just deployed' worst case).",
        "* The search semantics differ (LIKE substring vs FULLTEXT word prefix, see the README); result counts for "
        "the benchmark words are comparable but not identical.",
        "* Re-run at least 3 times and report the median run if the numbers are used externally."]
    report_path = os.path.join(results_dir, "REPORT.md")
    with open(report_path, "w") as handle:
        handle.write("\n".join(out) + "\n")
    print("\n".join(out))
    print("\nwritten: " + report_path)


if __name__ == "__main__":
    main()
