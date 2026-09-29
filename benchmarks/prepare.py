"""Preparation for the Sprint 23 benchmark runs.

Creates the link pool consumed by benchmarks/k6-scenario.js (one 'bench-hit'
code plus a pool of never-requested codes) and empties Redis so the first
'run -e SCENARIO=miss' measures genuine PostgreSQL lookups.

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
    conn = http.client.HTTPConnection(*BASE, timeout=30)
    body = json.dumps({"originalUrl": "https://example.com/bench", "alias": alias})
    conn.request("POST", "/api/v1/links", body, {"Content-Type": "application/json"})
    status = conn.getresponse().status
    conn.close()
    return status


def main() -> None:
    started = time.time()
    aliases = ["bench-hit"] + [f"p{i}" for i in range(POOL)]
    counter = itertools.count()
    failures = []

    def worker() -> None:
        while True:
            i = next(counter)
            if i >= len(aliases):
                return
            if post(aliases[i]) != 201:
                failures.append(aliases[i])

    threads = [threading.Thread(target=worker) for _ in range(200)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    if failures:
        raise SystemExit(f"{len(failures)} creations failed, first: {failures[:3]}")

    subprocess.run(
        ["docker", "exec", "urlshorter-redis-dev", "redis-cli", "FLUSHALL"],
        check=True, capture_output=True)
    with open("benchmarks/links.json", "w", encoding="utf-8") as fh:
        json.dump({"codes": aliases[1:]}, fh)
    print(f"created {len(aliases)} links in {time.time() - started:.1f}s; "
          f"redis flushed; links.json written")


if __name__ == "__main__":
    main()
