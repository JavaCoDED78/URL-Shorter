"""Preparation for the Sprint 23 benchmark runs.

Creates the link pool consumed by benchmarks/k6-scenario.js (a pool of
never-requested codes) and empties Redis so the first
'run -e SCENARIO=miss' measures genuine PostgreSQL lookups. The 'bench-hit'
redirect is served by a benchmark-only endpoint (shortener.benchmark.enabled)
and needs no link row — 'bench-hit' is a reserved alias and cannot be
created.

Usage: python benchmarks/prepare.py [pool_size]
"""
import http.client
import itertools
import json
import subprocess
import sys
import threading
import time

BASE = "localhost", 8080
POOL = int(sys.argv[1]) if len(sys.argv) > 1 else 8000


def post(alias: str) -> int:
    # Windows exhausts ephemeral client ports when hundreds of threads churn
    # short-lived connections (WinError 10048) — retry instead of dropping
    # the alias silently.
    for attempt in range(5):
        try:
            conn = http.client.HTTPConnection(*BASE, timeout=30)
            body = json.dumps({"originalUrl": "https://example.com/bench", "alias": alias})
            conn.request("POST", "/api/v1/links", body, {"Content-Type": "application/json"})
            status = conn.getresponse().status
            conn.close()
            return status
        except OSError:
            if attempt == 4:
                raise
            time.sleep(0.1)


def main() -> None:
    started = time.time()
    aliases = [f"p{i}" for i in range(POOL)]
    counter = itertools.count()
    failures = []

    def worker() -> None:
        while True:
            i = next(counter)
            if i >= len(aliases):
                return
            try:
                # 409 = alias already exists from a previous prepare run —
                # the pool only has to exist, so re-runs stay idempotent.
                if post(aliases[i]) not in (201, 409):
                    failures.append(aliases[i])
            except OSError:
                failures.append(aliases[i])

    threads = [threading.Thread(target=worker) for _ in range(200)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    # Sequential retry of the stragglers — one connection at a time lets
    # the ephemeral port pool recover.
    for _ in range(10):
        if not failures:
            break
        retry, failures = failures, []
        for alias in retry:
            try:
                if post(alias) not in (201, 409):
                    failures.append(alias)
            except OSError:
                failures.append(alias)
    if failures:
        raise SystemExit(f"{len(failures)} creations failed, first: {failures[:3]}")

    # Dev compose names the container urlshorter-redis-dev, the full stack
    # urlshorter-redis — pick whichever is running.
    redis_container = "urlshorter-redis-dev"
    if subprocess.run(["docker", "inspect", redis_container],
                      capture_output=True).returncode != 0:
        redis_container = "urlshorter-redis"
    subprocess.run(
        ["docker", "exec", redis_container, "redis-cli", "FLUSHALL"],
        check=True, capture_output=True)
    with open("benchmarks/links.json", "w", encoding="utf-8") as fh:
        json.dump({"codes": aliases[1:]}, fh)
    print(f"created {len(aliases)} links in {time.time() - started:.1f}s; "
          f"redis flushed; links.json written")


if __name__ == "__main__":
    main()
