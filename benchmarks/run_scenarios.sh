#!/bin/bash
# Runs the per-scenario benchmark suite (Task 23.1): hit, stats, miss,
# delete, create, spike10k. Each writes results/<sc>.ndjson + <sc>.log.
# Read-only scenarios first; stateful (delete/create) run against a
# truncated DB so their deterministic aliases (c-*/d-*) never collide.
set -u
cd "$(dirname "$0")" || exit 1          # benchmarks/  (so open('./links.json') resolves)
export SHORTENER_BENCHMARK_ENABLED=true
export BASE_URL=http://localhost:8080

TRUNC='docker exec urlshorter-postgres psql -U postgres -d urlshortener -c "TRUNCATE links"'

run() {
  local sc="$1"
  local out=$(cygpath -w "results/${sc}.ndjson")
  local log="results/${sc}.log"
  echo ">>> SCENARIO=$sc  -> $out"
  SCENARIO="$sc" k6 run --out json="$out" -e BASE_URL="$BASE_URL" k6-scenario.js > "$log" 2>&1
  echo "<<< SCENARIO=$sc exit=$?"
}

# read-only
run hit
run stats
run miss
# stateful: clean slate, then delete (d-*) then create (c-*)
eval "$TRUNC" >/dev/null 2>&1
run delete
run create
eval "$TRUNC" >/dev/null 2>&1
# pure benchmark endpoint again
run spike10k
echo "ALL SCENARIOS DONE"
