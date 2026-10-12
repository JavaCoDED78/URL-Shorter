# DEVELOPMENT.md

Developer guide: how to set up, run, debug and inspect the URL Shortener
locally. For the product overview and API reference see [README.md](README.md);
for recorded design decisions see [docs/adr/](docs/adr/).

## Prerequisites

- **JDK 25** — the build uses a Gradle toolchain, so any JDK 17+ can run
  Gradle itself; the compiler toolchain downloads Amazon Corretto 25
  automatically on first build.
- **Docker** — required for the dev databases and for all integration tests
  (Testcontainers).
- **Git Bash** is assumed below; on plain PowerShell the same commands work
  with `./gradlew` → `.\gradlew.bat` and `export` → `$env:`.

## Local setup

The fastest full loop is the production-like compose stack:

```bash
docker compose up --build      # app + PostgreSQL + Redis, health-gated
```

For development, run only the databases in Docker and the app from Gradle or
the IDE:

```bash
docker compose -f docker-compose.dev.yml up -d
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/urlshortener ./gradlew bootRun
```

Notes:

- Flyway migrations run automatically on startup — the schema is always
  created by the app, never by hand.
- The app on the default profile expects PostgreSQL on `localhost:5432` and
  Redis on `localhost:6379`. Every value is env-overridable
  (see the configuration table in the README).
- `./gradlew bootRun` uses the `local` profile diagnostics
  (`StartupDiagnostics` logs effective configuration on boot, without
  secrets).

### Windows: reserved port ranges (winnat)

After Docker Desktop restarts, Windows NAT sometimes reserves the
`5401–5500` TCP range, which blocks host bindings in `5432–5499`. Symptom:
`docker compose up` fails with a port-binding error, or the app cannot
reach PostgreSQL. Check and work around:

```bash
netsh interface ipv4 show excludedportrange protocol=tcp
# if 5432-5499 fall inside a reserved range:
POSTGRES_PORT=5540 docker compose -f docker-compose.dev.yml up -d
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5540/urlshortener ./gradlew bootRun
```

Testcontainers is unaffected — it binds dynamic ports.

### Windows: Docker Desktop not running

Docker Desktop does not auto-start after a reboot. Before any docker
command:

```bash
powershell.exe Start-Process 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
# then poll until ready:
docker ps
```

## IDE setup

- Import the project as a **Gradle** project (Kotlin DSL build).
- The Gradle JVM can be 17+; the **toolchain** handles Java 25 for compile
  and test execution.
- Recommended run configuration: `bootRun` with the `local` profile and
  `SPRING_DATASOURCE_URL` pointing at the dev container port (see above).
- `./gradlew` in this repository historically lost its executable bit —
  if `./gradlew` is denied, run `chmod +x gradlew` (the CI workflow does the
  same).

## Build and test

```bash
./gradlew clean build        # everything: compile, unit + integration tests, jar
./gradlew test               # full test suite (Testcontainers — Docker must be up)
./gradlew test --tests '*Test'   # pure-JVM unit suites only, no Docker needed
```

- Integration tests (`*IT`) start **real PostgreSQL and Redis containers**
  via `@ServiceConnection` — no local databases are touched.
- The heavy concurrency test (`ConcurrencyIT`) creates 10 000 virtual-thread
  clients; on loaded machines it may need a retry or two (the suite is
  hardened for exactly that).

## Debugging

- **From the IDE:** run `bootRun` in debug mode; breakpoints in controllers,
  `WriteLinkService`, `ReadLinkService`, `CounterFlushService` and the Lua
  script wrapper all work normally.
- **Logging:** Spring Boot logs at INFO; `com.javaded78.urlshorter` does not
  log payloads or secrets. The startup diagnostics line prints the
  effective non-secret configuration once.
- **Actuator:** `http://localhost:8080/actuator/health` (with readiness and
  liveness probes) and `http://localhost:8080/actuator/metrics` (HikariCP
  pool, Redis latency, cache counters, flush counters, HTTP server metrics).
- **Remote debugging a container:** the compose `app` service is started
  without a JDWP agent by default; add
  `JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`
  and expose port 5005 when needed.

## Redis inspection

Against the dev container (`urlshorter-redis-dev`):

```bash
# open a CLI
docker exec -it urlshorter-redis-dev redis-cli

# what is cached right now
TYPE link:abc1234        # string (JSON document)
GET  link:abc1234        # {"code":..., "originalUrl":..., "expiresAt":...}
TTL  link:abc1234        # min(configured TTL, remaining lifetime)

# click counters
GET clicks:abc1234       # pending clicks not yet flushed; missing = nothing pending

# flush a single counter by hand (atomic GET+DEL — the production drain does
# exactly this for a batch of keys in one Lua call; see ADR-004)
EVAL "local v = redis.call('GET', KEYS[1]); if v == false then return nil end; redis.call('DEL', KEYS[1]); return tonumber(v)" 1 clicks:abc1234

# never use KEYS in production; SCAN is fine
SCAN 0 MATCH "link:*" COUNT 100

# full reset (dev only!)
FLUSHALL
```

Keyspace conventions: keys are lowercased (consistent with the
case-insensitive lookup index); `link:` entries are JSON strings with a
stable wire format (see ADR-002); `clicks:` counters are plain strings with
no TTL by design (ADR-003/004).

## Monitoring

The monitoring stack (Prometheus + Grafana) runs alongside the application in
the Docker Compose stack. All configuration is **as code** (ADR-008).

### Open Prometheus

```
http://localhost:9090
```

### Open Grafana

```
http://localhost:14000
# default credentials: admin / admin (set GF_SECURITY_ADMIN_PASSWORD via env)
```

### Check Prometheus target

```bash
# In the Prometheus UI: Status → Targets
# Or via API:
curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job, health, labels}'
```

Expected: `urlshorter-app` target with `health: "up"`.

### Find a metric

```bash
# Search in Prometheus UI: Graph → "Expression" → type metric name
# Or via API:
curl "http://localhost:9090/api/v1/label/__name__/values" | jq '.data[]' | grep -i "urlshorter"
```

Common business metrics (names follow the observability contract —
`docs/observability-contract.md`):

```text
urlshorter_linksCreated_total           # counter
urlshorter_linksRedirected_total        # counter
urlshorter_linksNotFound_total          # counter
urlshorter_linksExpired_total           # counter
urlshorter_linksDeleted_total           # counter

urlshorter_cacheHit_total               # counter
urlshorter_cacheMiss_total              # counter

urlshorter_clicksIncremented_total      # counter
urlshorter_clicksIncrementFailed_total  # counter
urlshorter_clicksFlushed_total          # counter
urlshorter_clicksFlushFailed_total      # counter

http_server_requests_seconds  # histogram (micrometer)
```

### Verify recording rules locally

```bash
# Check recording rules are loaded:
curl http://localhost:9090/api/v1/rules | jq '.data.groups[].rules[] | select(.type=="recording") | .name'

# Query a pre-computed metric (e.g. 5-minute redirect rate):
curl "http://localhost:9090/api/v1/query?query=urlshorter:link_redirected:rate5m"
```

### Verify alert rules locally

```bash
# Check alert rules are loaded:
curl http://localhost:9090/api/v1/rules | jq '.data.groups[].rules[] | select(.type=="alert") | .name'
```

### Change a dashboard

Dashboard JSON is under `monitoring/grafana/dashboards/url-shortener-overview.json`.
Changes are applied automatically on Grafana restart (or re-provisioning on
`docker compose up --force-recreate grafana`).

1. Edit the JSON file.
2. Restart the container: `docker compose restart grafana`
3. Refresh the browser.

### Change an alert rule

1. Edit `monitoring/prometheus/rules/alert-rules.yml`.
2. Restart Prometheus: `docker compose restart prometheus`
3. Verify in Prometheus UI: Status → Rules.

### Validate monitoring configuration

A standalone validator checks YAML syntax, PromQL validity, and Grafana
provisioning without Docker:

```bash
python monitoring/validate-monitoring-config.py
```

The smoke test runs the full pipeline (with Docker):

```bash
python monitoring/validate-monitoring-stack.py
```

See [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) — Tasks 29.12 and 29.11 for
details on CI validation and smoke testing.

## PostgreSQL inspection

```bash
docker exec -it urlshorter-postgres-dev psql -U postgres -d urlshortener

SELECT code, left(original_url, 40), click_count, expires_at FROM links;
SELECT count(*) FROM links;
EXPLAIN ANALYZE SELECT * FROM links WHERE lower(code) = lower('abc1234');
```

Schema facts worth knowing:

- `links.code` is the primary key; migration V2 adds the functional unique
  index `links_code_lower_uq` on `lower(code)` serving case-insensitive
  lookups.
- `click_count` is updated by a single batched `unnest` UPDATE from the
  flush — never per click.
- Applied migrations are never edited; schema changes ship as new V*
  migrations only (expand/contract for independent deployments).
- To reset dev data: `DELETE FROM links;` (and `FLUSHALL` in Redis).

## Benchmarks

Scripts live in `benchmarks/` (k6; run via `docker run grafana/k6` if k6
is not installed locally):

```bash
python benchmarks/prepare.py     # creates 8001 links + benchmarks/links.json
k6 run -e SCENARIO=hit benchmarks/k6-scenario.js
k6 run benchmarks/k6-main.js     # full 4-minute profile
```

Read the numbers only when the run reports **0 % failed checks** — a run
against missing codes measures 404 latency, not the redirect path (this
exact mistake invalidated a measurement once; see the Sprint 24 notes in
the README).

## Common failures

| Symptom | Cause | Fix |
|---|---|---|
| `docker compose` port binding fails on 5432–5499 | winnat reserved range | use `POSTGRES_PORT=55xx`, see above |
| `docker: error during connect` | Docker Desktop not running | start it, wait for `docker ps` |
| `./gradlew: Permission denied` | executable bit lost in checkout | `chmod +x gradlew` |
| Tests fail with container startup timeouts | Docker under heavy load / first image pull | re-run; Ryuk and image pulls are one-time costs |
| `Connection refused` from the app | dev containers down, or app started against the wrong `SPRING_DATASOURCE_URL` | `docker compose -f docker-compose.dev.yml ps`; check `SPRING_DATASOURCE_URL` |
| Create returns `503` quickly | PostgreSQL down or pool timeout (5 s) | check the database container health; this is the intended degradation, not a hang |
| Redirect returns `503` for an uncached code | PostgreSQL down, cache miss | cached codes still return `302` — by design |
| `413` on a legitimate create | body over `SHORTENER_MAX_PAYLOAD_SIZE` (default 16 KB) | the limit is far above a 2048-char URL; check for accidental padding |
| Swagger UI shows stale endpoints | browser cache | hard-refresh; the spec is served from `/v3/api-docs` live |
