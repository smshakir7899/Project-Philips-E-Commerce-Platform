#!/usr/bin/env python3
"""
Load generator for the product-service performance experiment (Python 3.6+, standard library only).

What it does, in order:
  1. builds a DETERMINISTIC request list from --seed (same seed => byte-identical list in every scenario),
  2. optionally flushes the Redis product cache (--flush-cache),
  3. sends --warmup requests (a separate list from seed+1 with the same distribution), not recorded,
  4. resets server counters (POST /api/products/admin/perf/reset),
  5. snapshots MySQL global status (--mysql-docker),
  6. runs the measured requests with --concurrency worker threads, recording latency + status of each,
  7. reads server counters (GET /api/products/admin/perf/stats) and MySQL status again,
  8. writes everything (raw samples included) as JSON to --out.

Request types (the read workload of a catalog front end):
  list            GET /api/products?categoryId=C&page=P&size=20&sort=name,asc
  category_price  GET /api/products?categoryId=C&minPrice=A&maxPrice=B&page=P&size=20&sort=price,asc
  search          GET /api/products?q=WORD&page=0&size=20&sort=name,asc
  detail          GET /api/products/{id}   (80% from a fixed "hot" set, 20% uniform over the id range)

Latency = time from sending the request until the full response body has been read (time.perf_counter).
Percentiles use the NEAREST-RANK method: p-th percentile = sorted[ceil(p/100 * N) - 1]. It always returns an
observed sample (no interpolation), which is what most load tools report.

Each worker thread keeps ONE persistent HTTP/1.1 connection (keep-alive) via http.client. Opening a new TCP
connection per request (plain urllib.request.urlopen) would add the same constant cost to every scenario,
dilute the differences and can exhaust ephemeral ports at high request rates. Admin calls use urllib.
"""
import argparse
import http.client
import json
import math
import os
import platform
import random
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

SEARCH_WORDS = ["toothbrush", "sonicare", "airfryer", "espresso", "shaver", "purifier", "lightstrip",
                "blender", "kettle", "trimmer", "humidifier", "dryer", "steriliser", "oneblade"]
PRICE_BANDS = [(0, 50), (50, 100), (100, 200), (200, 500), (500, 1100)]
DEFAULT_MIX = "list=30,category_price=20,search=20,detail=30"
MYSQL_STATUS_VARIABLES = ["Questions", "Innodb_rows_read", "Handler_read_rnd_next", "Handler_read_key",
                          "Handler_read_next", "Select_scan", "Sort_rows", "Created_tmp_disk_tables"]


# ----------------------------------------------------------------------------------------------- workload

def parse_mix(text):
    mix = []
    for part in text.split(","):
        name, weight = part.split("=")
        name = name.strip()
        if name not in ("list", "category_price", "search", "detail"):
            raise SystemExit("unknown request type in --mix: " + name)
        mix.append((name, float(weight)))
    return mix


def build_workload(seed, count, args):
    """Deterministic: depends only on (seed, count, args) - never on time or server responses."""
    rng = random.Random(seed)
    # the hot set is derived from the BASE seed so warmup (seed+1) and measured run share the same hot products
    hot_rng = random.Random(args.seed)
    id_range = range(args.id_min, args.id_max + 1)
    hot_ids = hot_rng.sample(id_range, min(args.hot_ids, len(id_range)))
    names = [m[0] for m in args.mix]
    weights = [m[1] for m in args.mix]
    pages = [0, 0, 0, 0, 1, 1, 1, 2, 2, 3, 4]  # users mostly look at the first pages
    requests = []
    for _ in range(count):
        kind = rng.choices(names, weights=weights)[0]
        category = rng.randint(1, args.categories)
        if kind == "list":
            path = "/api/products?categoryId=%d&page=%d&size=20&sort=name,asc" % (category, rng.choice(pages))
        elif kind == "category_price":
            low, high = rng.choice(PRICE_BANDS)
            path = ("/api/products?categoryId=%d&minPrice=%d&maxPrice=%d&page=%d&size=20&sort=price,asc"
                    % (category, low, high, rng.choice([0, 0, 0, 1])))
        elif kind == "search":
            path = "/api/products?q=%s&page=0&size=20&sort=name,asc" % urllib.parse.quote(rng.choice(SEARCH_WORDS))
        else:
            product_id = rng.choice(hot_ids) if rng.random() < 0.8 else rng.choice(id_range)
            path = "/api/products/%d" % product_id
        requests.append({"type": kind, "path": path})
    return requests


# ----------------------------------------------------------------------------------------------- HTTP

class KeepAliveClient(object):
    """One persistent connection per worker thread."""

    def __init__(self, base_url, timeout):
        parsed = urllib.parse.urlparse(base_url)
        self.host = parsed.hostname
        self.port = parsed.port or (443 if parsed.scheme == "https" else 80)
        self.https = parsed.scheme == "https"
        self.prefix = parsed.path.rstrip("/")
        self.timeout = timeout
        self.local = threading.local()

    def _connection(self):
        conn = getattr(self.local, "conn", None)
        if conn is None:
            cls = http.client.HTTPSConnection if self.https else http.client.HTTPConnection
            conn = cls(self.host, self.port, timeout=self.timeout)
            self.local.conn = conn
        return conn

    def _reset(self):
        conn = getattr(self.local, "conn", None)
        if conn is not None:
            conn.close()
        self.local.conn = None

    def get(self, path, request_id):
        headers = {"Accept": "application/json", "X-Correlation-Id": "bench-%d" % request_id}
        for attempt in (1, 2):
            conn = self._connection()
            start = time.perf_counter()
            try:
                conn.request("GET", self.prefix + path, headers=headers)
                response = conn.getresponse()
                response.read()
                elapsed_ms = (time.perf_counter() - start) * 1000.0
                if response.getheader("Connection", "").lower() == "close":
                    self._reset()
                return response.status, elapsed_ms, None
            except (http.client.RemoteDisconnected, BrokenPipeError, ConnectionResetError) as ex:
                # the server closed an idle keep-alive connection: reconnect once, do not count it as an error
                self._reset()
                if attempt == 2:
                    return 0, (time.perf_counter() - start) * 1000.0, repr(ex)
            except (socket.timeout, OSError, http.client.HTTPException) as ex:
                self._reset()
                return 0, (time.perf_counter() - start) * 1000.0, repr(ex)
        return 0, 0.0, "unreachable"


def admin_call(base_url, method, path, timeout=30):
    request = urllib.request.Request(base_url.rstrip("/") + path, method=method,
                                     headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return response.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as ex:
        return ex.code, None
    except (urllib.error.URLError, OSError) as ex:
        print("WARN admin call %s %s failed: %s" % (method, path, ex), file=sys.stderr)
        return 0, None


def run_requests(client, requests, concurrency, offset=0):
    """Returns (samples, wall_seconds). samples[i] = [type, status, latency_ms, error]."""
    samples = [None] * len(requests)

    def task(index):
        request = requests[index]
        status, latency, error = client.get(request["path"], offset + index)
        samples[index] = [request["type"], status, round(latency, 3), error]

    start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        list(pool.map(task, range(len(requests))))
    return samples, time.perf_counter() - start


# ----------------------------------------------------------------------------------------------- stats

def percentile(sorted_values, p):
    """Nearest-rank percentile: the smallest value such that at least p% of samples are <= it."""
    if not sorted_values:
        return None
    rank = int(math.ceil(p / 100.0 * len(sorted_values)))
    return sorted_values[max(rank, 1) - 1]


def summarize(samples, wall_seconds):
    latencies = sorted(s[2] for s in samples)
    errors = sum(1 for s in samples if s[1] == 0 or s[1] >= 400)
    count = len(samples)
    if count == 0:
        return {"count": 0}
    return {
        "count": count,
        "errors": errors,
        "errorRate": round(errors / float(count), 4),
        # per-type throughput = that type's share of the overall measured run (same wall clock)
        "throughputRps": round(count / wall_seconds, 2) if wall_seconds > 0 else None,
        "meanMs": round(sum(latencies) / count, 3),
        "minMs": latencies[0],
        "p50Ms": percentile(latencies, 50),
        "p90Ms": percentile(latencies, 90),
        "p95Ms": percentile(latencies, 95),
        "p99Ms": percentile(latencies, 99),
        "maxMs": latencies[-1],
    }


# ----------------------------------------------------------------------------------------------- environment

def run_command(command, timeout=10, env=None, stdin_text=None):
    try:
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout,
                                   env=env, input=stdin_text.encode() if stdin_text else None)
        if completed.returncode != 0:
            return None
        return completed.stdout.decode("utf-8", "replace").strip()
    except (OSError, subprocess.SubprocessError):
        return None


def environment():
    uname = platform.uname()
    return {
        "uname": " ".join([uname.system, uname.node, uname.release, uname.version, uname.machine]),
        "cpuCount": os.cpu_count(),
        "python": platform.python_version(),
        "dockerServerVersion": run_command(["docker", "version", "--format", "{{.Server.Version}}"]),
        "dockerComposeVersion": run_command(["docker", "compose", "version", "--short"]),
    }


def mysql_status(args):
    """SHOW GLOBAL STATUS through docker compose (the MySQL port need not be published)."""
    sql = "SHOW GLOBAL STATUS WHERE Variable_name IN (%s)" % ",".join("'%s'" % v for v in MYSQL_STATUS_VARIABLES)
    command = ["docker", "compose"]
    if args.compose_file:
        command += ["-f", args.compose_file]
    command += ["exec", "-T", "-e", "MYSQL_PWD=" + args.mysql_root_password, args.mysql_service,
                "mysql", "-uroot", "-N", "-B", "-e", sql]
    output = run_command(command, timeout=30)
    if output is None:
        print("WARN could not read MySQL status via docker compose", file=sys.stderr)
        return None
    values = {}
    for line in output.splitlines():
        parts = line.split("\t")
        if len(parts) == 2 and parts[1].isdigit():
            values[parts[0]] = int(parts[1])
    return values


def delta(before, after):
    if not before or not after:
        return None
    return dict((key, after[key] - before.get(key, 0)) for key in after)


# ----------------------------------------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description="product-service benchmark (stdlib only)")
    parser.add_argument("--base-url", default="http://localhost:8081")
    parser.add_argument("--requests", type=int, default=2000, help="measured requests")
    parser.add_argument("--concurrency", type=int, default=16, help="worker threads")
    parser.add_argument("--warmup", type=int, default=500, help="unrecorded requests before measuring (0 = none)")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--scenario", default="adhoc", help="label, e.g. S1")
    parser.add_argument("--description", default="")
    parser.add_argument("--out", required=True, help="JSON result file")
    parser.add_argument("--mix", default=DEFAULT_MIX, help="request type weights, default " + DEFAULT_MIX)
    parser.add_argument("--id-min", type=int, default=100000, help="first product id for detail requests")
    parser.add_argument("--id-max", type=int, default=149999, help="last product id for detail requests")
    parser.add_argument("--hot-ids", type=int, default=500, help="size of the popular product set (80%% of details)")
    parser.add_argument("--categories", type=int, default=8)
    parser.add_argument("--timeout", type=float, default=30.0, help="per-request timeout in seconds")
    parser.add_argument("--flush-cache", action="store_true", help="DELETE /api/products/admin/cache first")
    parser.add_argument("--mysql-docker", action="store_true",
                        help="capture SHOW GLOBAL STATUS deltas via 'docker compose exec mysql'")
    parser.add_argument("--mysql-service", default="mysql")
    parser.add_argument("--compose-file", default=None)
    parser.add_argument("--mysql-root-password", default=os.environ.get("MYSQL_ROOT_PASSWORD", "root_pwd"))
    args = parser.parse_args()
    args.mix = parse_mix(args.mix)

    measured = build_workload(args.seed, args.requests, args)
    warmup = build_workload(args.seed + 1, args.warmup, args) if args.warmup > 0 else []
    client = KeepAliveClient(args.base_url, args.timeout)

    status, _ = admin_call(args.base_url, "GET", "/actuator/health")
    if status != 200:
        raise SystemExit("service not healthy at %s (status %s)" % (args.base_url, status))

    if args.flush_cache:
        status, _ = admin_call(args.base_url, "DELETE", "/api/products/admin/cache")
        print("cache flush -> HTTP %s" % status)
    if warmup:
        print("[%s] warmup: %d requests ..." % (args.scenario, len(warmup)))
        run_requests(client, warmup, args.concurrency, offset=10 ** 7)

    admin_call(args.base_url, "POST", "/api/products/admin/perf/reset")
    _, stats_before = admin_call(args.base_url, "GET", "/api/products/admin/perf/stats")
    mysql_before = mysql_status(args) if args.mysql_docker else None

    print("[%s] measuring: %d requests, concurrency %d ..." % (args.scenario, len(measured), args.concurrency))
    started_at = datetime.now(timezone.utc).isoformat()
    samples, wall = run_requests(client, measured, args.concurrency)

    _, stats_after = admin_call(args.base_url, "GET", "/api/products/admin/perf/stats")
    mysql_after = mysql_status(args) if args.mysql_docker else None

    by_type = {}
    for kind in sorted(set(s[0] for s in samples)):
        by_type[kind] = summarize([s for s in samples if s[0] == kind], wall)
    overall = summarize(samples, wall)
    status_counts = {}
    for s in samples:
        status_counts[str(s[1])] = status_counts.get(str(s[1]), 0) + 1

    derived = {"statementsPerRequest": None, "cacheHitRatio": None}
    if stats_after:
        hib = stats_after.get("hibernate") or {}
        if hib.get("prepareStatementCount") is not None and overall["count"]:
            derived["statementsPerRequest"] = round(hib["prepareStatementCount"] / float(overall["count"]), 3)
        cache = stats_after.get("cache") or {}
        lookups = (cache.get("hits") or 0) + (cache.get("misses") or 0)
        if stats_after.get("cacheEnabled") and lookups:
            derived["cacheHitRatio"] = round(cache["hits"] / float(lookups), 4)
    mysql_delta = delta(mysql_before, mysql_after)
    if mysql_delta and overall["count"]:
        derived["mysqlQuestionsPerRequest"] = round(mysql_delta.get("Questions", 0) / float(overall["count"]), 3)
        derived["innodbRowsReadPerRequest"] = round(mysql_delta.get("Innodb_rows_read", 0) / float(overall["count"]), 1)

    result = {
        "scenario": args.scenario,
        "description": args.description,
        "startedAt": started_at,
        "config": {
            "baseUrl": args.base_url, "requests": args.requests, "concurrency": args.concurrency,
            "warmup": args.warmup, "seed": args.seed, "mix": dict(args.mix), "idMin": args.id_min,
            "idMax": args.id_max, "hotIds": args.hot_ids, "flushCache": args.flush_cache,
            "percentileMethod": "nearest-rank",
        },
        "environment": environment(),
        "wallSeconds": round(wall, 3),
        "overall": overall,
        "byType": by_type,
        "statusCounts": status_counts,
        "server": {"before": stats_before, "after": stats_after},
        "mysqlStatusDelta": mysql_delta,
        "derived": derived,
        "samples": samples,
    }
    out_dir = os.path.dirname(os.path.abspath(args.out))
    if not os.path.isdir(out_dir):
        os.makedirs(out_dir)
    with open(args.out, "w") as handle:
        json.dump(result, handle, indent=1)

    print("[%s] %d req in %.2fs -> %.1f req/s, mean %.2f ms, p50 %.2f, p95 %.2f, p99 %.2f, errors %d"
          % (args.scenario, overall["count"], wall, overall["throughputRps"], overall["meanMs"], overall["p50Ms"],
             overall["p95Ms"], overall["p99Ms"], overall["errors"]))
    print("[%s] statements/request %s, cache hit ratio %s -> %s"
          % (args.scenario, derived["statementsPerRequest"], derived["cacheHitRatio"], args.out))
    if overall["errors"]:
        print("WARN %d failed requests (status counts %s)" % (overall["errors"], status_counts), file=sys.stderr)


if __name__ == "__main__":
    main()
