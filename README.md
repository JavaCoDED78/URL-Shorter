# URL Shortener

A production-oriented URL shortening service built with **Spring Boot 4.1.1**, **Java 25**, **PostgreSQL 17**, and **Redis 7**.

The service uses PostgreSQL as the source of truth, Redis for low-latency redirects and click buffering, Flyway for schema migrations, and Testcontainers for realistic integration testing.

![CI](https://github.com/your-username/url-shortener/actions/workflows/ci.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)

## ✨ Features

- **URL shortening** with a random 7-character Base62 code (configurable via `SHORTENER_CODE_LENGTH`).
- **Case-insensitive codes**: `GET /AbC1234` resolves the same link as `abc1234`.
- **Custom aliases**, for example `/my-link`.
- **Link expiration (TTL)** with `expiresAt`.
- **Cache-aside redirects**: hot links are served from Redis without a PostgreSQL query; the cached value carries `expiresAt`, so expiry is always re-checked on a hit.
- **Click buffering in Redis** with atomic `INCR`, flushed to PostgreSQL every 5 seconds by a Lua script (`GET`+`DEL` atomic — no lost increments).
- **Stats without flush lag**: `clickCount` = persisted PostgreSQL count + pending Redis counter.
- **Graceful infrastructure degradation**: a PostgreSQL outage answers create/miss lookups with a fast `503` (bounded by a 5 s pool timeout) while Redis cache hits keep redirecting; a failed flush restores counters instead of losing clicks.
- **Request size limit**: oversized bodies are rejected with `413` before parsing.
- **RFC 9457 error responses** based on Spring's `ProblemDetail`.
- **OpenAPI 3 documentation** with interactive Swagger UI.
- **Java virtual threads** enabled through a single Spring Boot property.
- **Integration testing with Testcontainers** against real PostgreSQL and Redis, including live failure injection (stopping the real containers).
- **One-command local startup** using Docker Compose.

## 🛠 Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.1.1 |
| Web | Spring Web |
| Persistence | Spring Data JPA + Hibernate |
| Database | PostgreSQL 17 |
| Migrations | Flyway |
| Cache & counters | Redis 7 + Spring Data Redis |
| Atomic operations | Redis Lua scripts |
| API documentation | springdoc-openapi + Swagger UI |
| Testing | JUnit 5, Testcontainers, Awaitility, AssertJ |
| Build | Gradle Kotlin DSL |
| CI | GitHub Actions |
| Runtime | Java virtual threads |
| Metrics | Micrometer + Spring Boot Actuator + Prometheus |
| Dashboards | Grafana |
| Monitoring configuration | Prometheus/Grafana configuration as code |

> **Versioning note:** pin exact dependency versions in Gradle and keep the README badge versions aligned with the actual build configuration.

### Virtual threads (Sprint 14)

Enabled via `spring.threads.virtual.enabled: true`. Verified by `VirtualThreadsIT`:

- HTTP request lifecycle — one virtual thread per request (Tomcat);
- `@Scheduled` click flush — runs on a virtual thread;
- a 60-way concurrent burst does not inflate the server platform thread count.

Blocking operations under virtual threads: JPA calls block on a Hikari checkout
(pool pinned at **10** — cheap threads must not become a reason to widen the
pool, see `ConnectionPoolIT`); Redis calls park the virtual thread on Lettuce's
non-blocking Netty I/O.

Local benchmark (cache-hit redirect, dev laptop, Python thread-pool client —
`curl | xargs` was spawn-bound and useless above ~45 rps):

| Scenario (workers × requests) | OFF: throughput / avg / p95 | ON: throughput / avg / p95 |
|---|---|---|
| 100 × 2000 | 4453 rps / 21 ms / 36 ms | 3279 rps / 29 ms / 66 ms |
| 400 × 4000 | 4109 rps / 51 ms / 427 ms | 4217 rps / 50 ms / 420 ms |
| CPU, 200 × 6000 | 4.0 s total | 3.0 s total |

Memory at rest: ~337 MB (OFF) vs ~389 MB (ON, carrier + pinning arenas).
DB saturation: identical — 10/10 Hikari connections in both modes.

**Reading:** for the cache-hit redirect path both modes are within noise —
the ceiling is Redis, not threads. Virtual threads buy headroom above the
platform-thread limit without tuning `server.tomcat.threads.max`, at ~50 MB
extra RSS. The PostgreSQL pool stays the hard concurrency ceiling for DB
access in both modes.

### Performance baseline (Sprint 23)

Measured with k6 (scripts in `benchmarks/`) against the dev stack on a
desktop machine — an anchor for future comparisons, not an absolute claim.
All runs: **0% errors**.

Per-endpoint (30 s each; k6 `http_req_duration`; RPS = requests/30 s):

| Scenario | RPS | avg | p95 | Notes |
|---|---|---|---|---|
| Redirect — Redis hit | ~5 090 | 9.6 ms | 13.0 ms | 152 779 requests |
| Redirect — PostgreSQL miss | ~3 210 | 9.1 ms | 13.1 ms | 96 327 requests, uncached pool |
| Create link | ~1 267 | 23.5 ms | 32.2 ms | write path + Hikari checkout |
| Stats | ~4 954 | 9.7 ms | 14.5 ms | PostgreSQL read |
| Delete | ~1 244 calls/s | 15.9 ms | 21.5 ms | create + delete per iteration |
| 10 000 concurrent redirects | ~2 999 | 2.4 ms | 4.6 ms | 12 001 requests / 4 s, 0 dropped |

Full load profile (warm-up → sustained → 500-VU spike → cool-down,
4 min): **1 104 481 requests**, 4 602 rps avg, p95 86 ms, 0 failures.
Resource ceilings observed during the profile: CPU ≈ 2.4 cores avg /
3.6 cores at the spike; RSS ≈ 523 MB avg / 809 MB peak; PostgreSQL
connections peaked at 11 (10-connection pool + the monitoring session);
Redis traffic ≈ 2 ops per redirect (GET + INCR) — measured 24 040 Redis
commands for 12 001 spike requests, exactly consistent.

Flush interval comparison (60 s, 50 VUs, cache-hit redirect):

| `SHORTENER_FLUSH_INTERVAL` | Requests | p95 | Flushes/min |
|---|---|---|---|
| PT1S | 335 438 | 12.50 ms | 53 |
| PT5S (default) | 352 193 | 12.04 ms | 12 |
| PT10S | 342 727 | 12.46 ms | 6 |

Flush runs off the request path — redirect latency is identical within
noise at every interval; the interval only trades click-count freshness
against PostgreSQL write frequency. The **default stays PT5S**: sub-second
stats lag is not a stated requirement, and 5 s already bounds the loss
window (Sprint 8) at a quarter of the write load of PT1S.

Reproduce: `python benchmarks/prepare.py` then
`k6 run -e SCENARIO=<hit|miss|create|stats|delete|spike10k> benchmarks/k6-scenario.js`
or `k6 run benchmarks/k6-main.js` for the full profile.

### Database notes (Sprint 24)

**Query plans** (`EXPLAIN ANALYZE` on 200 000 links): all three hot queries
are unique-index scans, sub-millisecond — `findByCodeIgnoreCase` via the
`links_code_lower_uq` functional index (0.05 ms), click update and delete
via `links_pkey` (0.19 / 0.11 ms). `PRIMARY KEY(code)` plus the migration-V2
case-insensitive index cover every lookup; no additional indexes.

**Per-click vs batch flush** (10 000 clicks, one transaction, psql over
`docker exec`): 10 000 individual `UPDATE`s — 1.96 s; one batched `unnest`
`UPDATE` — 0.49 s (~4×), before counting the per-click network roundtrip
the batched flush removes from the redirect path entirely.

**Connection pool** (Hikari metrics sampled at 2 s under the heaviest DB
path, sustained cache-miss load, both runs 0% errors on valid 302s):
pool 10 → active avg 2.1, p95 12.6 ms, connection queueing observed
(pending up to 10); pool 16 → active avg 3.1, p95 13.1 ms, queueing gone
(pending ≤ 3) — **but no faster**. Connection waiting at pool 10 is not
what costs latency on the miss path; the PostgreSQL lookup and cache
repopulate dominate. **Pool stays at 10** (`ConnectionPoolIT` pins it) —
sized from measured concurrency, not from the virtual-thread count.

### Redis notes (Sprint 25)

**Key inspection (25.1):** `link:{code}` — JSON string, e.g. 84 bytes for a
typical entry; `clicks:{code}` — plain `INCR` counter string. Keys are
lowercased to stay consistent with the case-insensitive lookup index.

**TTL audit (25.2):** `link:` entries carry
`min(configured TTL, remaining business lifetime)` — verified live: 1 h for
long-lived links, a 3-second link's entry auto-removed right at expiry, and
`DELETE` evicts its entry immediately. `clicks:` counters intentionally have
**no TTL** — a counter must survive until the flush persists it; the drain
script deletes the key after a successful read, and a failed flush restores
the value (Task 17.3).

**Serialization (25.3), measured:** JSON vs a compact `code|url|epoch`
string — 152 vs 104 bytes per key (payload 84 vs 41). Throughput of 20 000
pipelined GETs was **identical** (0.237 s both) — GET is O(1) and does not
care about 43 bytes. **JSON stays**: the saving is ~48 bytes/key (~30 MB for
200 k cached links — trivial), while the compact format breaks the Sprint 19
forward-compatible wire contract, loses human-readable values in
`redis-cli`, and needs delimiter escaping (URLs can contain `|`).

**Lua drain (25.4), verified live:** the drain script touches exactly one
key per execution (`GET`+`DEL` atomic), discovered by non-blocking `SCAN`
(count 100) — no `KEYS` scans, no unbounded key lists. 1 000 pipelined
drains: 0 errors, every counter returned its value, 0 keys left behind;
draining a missing key returns `nil` (idempotent). Error handling: script
failure leaves counters intact and surfaces as `clicks.flush.failed`
(`RedisFailureIT`).

## 🏛 Architecture

```text
                              ┌─────────────────────────────┐
                              │       Spring Boot app       │
                              │      Java 25 / Web         │
                              └──────────────┬──────────────┘
                                             │
                         ┌───────────────────┴───────────────────┐
                         │                                       │
                     Cache-aside                                INCR
                         │                                       │
                         ▼                                       ▼
                ┌─────────────────┐                    ┌─────────────────┐
                │    Redis 7      │                    │    Redis 7      │
                │ link:{code}     │                    │ clicks:{code}   │
                └────────┬────────┘                    └────────┬────────┘
                         │ cache miss                            │
                         ▼                                       │
                ┌─────────────────┐                              │
                │  PostgreSQL 17  │◄──── Lua atomic flush ───────┘
                │ source of truth │       every 5 seconds
                └─────────────────┘
                         ▲
                         │ Flyway
                         │
                    schema migrations
```

### Monitoring flow (Sprint 29)

The monitoring pipeline is fully reproducible from Git and requires no manual
Prometheus or Grafana configuration:

```text
Spring Boot application
        │
        ▼
Spring Boot Actuator
/actuator/prometheus
        │
        │ scrape
        ▼
Prometheus
├── recording rules
└── alert rules
        │
        │ PromQL
        ▼
Grafana
├── datasource provisioning
└── dashboard provisioning

Repository
└── monitoring/
    ├── prometheus/
    │   ├── prometheus.yml
    │   └── rules/
    │       ├── recording-rules.yml
    │       └── alert-rules.yml
    └── grafana/
        ├── dashboards/
        │   └── url-shortener-overview.json
        └── provisioning/
            ├── datasources/
            │   └── prometheus.yml
            └── dashboards/
                └── dashboards.yml
```

The repository version controls scrape configuration, rules, Grafana
datasource provisioning and dashboards. A clean local environment must
reproduce the monitoring setup from these files alone.

### Main request flows

#### Create link

```text
POST /api/v1/links
      │
      ▼
Validate request
      │
      ▼
Generate Base62 code
      │
      ▼
Insert into PostgreSQL
      │
      ▼
Return 201 Created
```

The database `UNIQUE` constraint is the final protection against code collisions. An optimistic `existsByCode(...)` check may reduce avoidable database conflicts, but it is not sufficient for concurrency by itself.

#### Redirect

```text
GET /{code}
      │
      ▼
Redis lookup
   ┌──┴───┐
   │      │
 HIT    MISS
   │      │
   │      ▼
   │  PostgreSQL
   │      │
   │      ▼
   │   Cache write
   │      │
   └──┬───┘
      ▼
Check expiration
      │
      ▼
INCR click counter
      │
      ▼
HTTP 302 → original URL
```

The cached representation contains `expiresAt`, so an expired link is not accidentally served just because its Redis entry has not yet expired.

#### Click counter flush

```text
Redirect request
      │
      ▼
Redis INCR
      │
      │ every 5 seconds
      ▼
Lua script
(GET + DEL atomically)
      │
      ▼
Batch UPDATE PostgreSQL
```

The Lua script prevents the classic read-then-delete race where increments
arriving between the two commands can be lost. The flush discovers counters
by non-blocking `SCAN` (never `KEYS`) and batches the persistence as one
`unnest` `UPDATE` — measured ~4× faster than per-click updates.

## 📁 Project Structure

```text
src/main/java/com/javaded78/urlshorter/
├── api/
│   ├── controller/       # REST endpoints (thin: validate → service → DTO)
│   ├── dto/              # request/response DTOs
│   └── error/            # ProblemDetail / exception handling
│
├── domain/
│   ├── model/            # domain objects (no Redis/JPA specifics)
│   ├── cache/            # cache port: CachedLink (stable wire format) + LinkCache
│   ├── counter/          # counter port: ClickCounter (no Redis types in signatures)
│   ├── repository/       # persistence port (Spring Data repository = JPA boundary)
│   └── service/          # application/domain services
│
├── infra/
│   ├── persistence/      # JPA entity, LinkRepository implementation concerns
│   └── redis/
│       ├── cache/        # Redis cache implementation (RedisLinkCache)
│       ├── counter/      # Redis click counters (RedisClickCounter)
│       ├── script/       # Lua scripts
│       └── flush/        # scheduled flush job
│
├── config/               # properties, filters, OpenAPI, startup diagnostics
└── (application classes end here)

monitoring/                # repo-root Prometheus/Grafana configuration as code
├── prometheus/
│   ├── prometheus.yml
│   └── rules/
│       ├── recording-rules.yml
│       └── alert-rules.yml
└── grafana/
    ├── dashboards/
    │   └── url-shortener-overview.json
    └── provisioning/
        ├── datasources/
        │   └── prometheus.yml
        └── dashboards/
            └── dashboards.yml
```

The service layer talks to Redis only through the `domain/cache` and
`domain/counter` ports — never through Redis command details. The
`domain/repository` port is a Spring Data repository, which is the
deliberate JPA boundary of the domain (see
[`docs/adr/`](docs/adr/) for the recorded decisions).

## 🚀 Quick Start

The easiest way to start the entire stack is Docker Compose.

### Prerequisites

- Docker
- Docker Compose
- Git

### Start

```bash
git clone https://github.com/your-username/url-shortener.git
cd url-shortener

docker compose up --build
```

The local stack includes the application, PostgreSQL, Redis, Prometheus and Grafana.

The application will be available at:

- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **Health:** http://localhost:8080/actuator/health
- **Prometheus metrics:** http://localhost:8080/actuator/prometheus

Prometheus and Grafana are provisioned automatically by Docker Compose. Their
host ports are defined in `docker-compose.yml`; no manual datasource or
dashboard setup is required.

## 💻 Local Development

For local development, run PostgreSQL and Redis in Docker and the Spring Boot application from your IDE or Gradle.

```bash
docker compose -f docker-compose.dev.yml up -d

./gradlew bootRun
```

Or start the application directly from the IDE.

Flyway migrations are applied automatically during application startup.

See [DEVELOPMENT.md](DEVELOPMENT.md) for the full developer guide (IDE
setup, debugging, Redis/PostgreSQL inspection, common failures, benchmarks).

## 📡 API

The management API is versioned by URL path: the current major version is
`/api/v1`. Breaking contract changes ship under a new prefix (`/api/v2`),
keeping `/api/v1` stable for existing clients. The public redirect endpoint
`GET /{code}` is intentionally not versioned — it is the entry point short
links are created for, and path versioning there would only make shared
codes longer.

| Method | Path | Description | Success / Error Codes |
|---|---|---|---|
| `POST` | `/api/v1/links` | Create a short link | `201`, `400`, `409` |
| `GET` | `/{code}` | Redirect to the original URL | `302`, `404`, `410` |
| `GET` | `/api/v1/links/{code}` | Get link details | `200`, `404` |
| `GET` | `/api/v1/links/{code}/stats` | Get click statistics | `200`, `404` |
| `DELETE` | `/api/v1/links/{code}` | Delete a link | `204`, `404` |

### Create a link

```bash
curl -X POST http://localhost:8080/api/v1/links \
  -H "Content-Type: application/json" \
  -d '{
    "originalUrl": "https://example.com/very/long/path",
    "expiresAt": "2026-12-31T23:59:59Z"
  }'
```

Response `201 Created`:

```json
{
  "code": "aB3xK92",
  "originalUrl": "https://example.com/very/long/path",
  "expiresAt": "2026-12-31T23:59:59Z",
  "clickCount": 0,
  "createdAt": "2026-01-15T10:15:30Z"
}
```

`expiresAt` is optional — omit it (or pass `null`) for a link that never
expires. A custom alias can be requested with `"alias": "my-link"`;
`code` in the response is the alias. Codes are case-insensitive.

Error cases:

```json
// 400 — invalid URL or malformed request (detail carries the validator message)
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "URL scheme must be one of [http, https]: ftp://example.com",
  "instance": "/api/v1/links"
}
```

```json
// 409 — generated code collision after bounded retries, or alias taken
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "code already taken: my-link",
  "instance": "/api/v1/links"
}
```

```json
// 413 — request body over the configured limit (default 16 KB)
{
  "type": "about:blank",
  "title": "Payload Too Large",
  "status": 413,
  "detail": "request body exceeds the maximum of 16384 bytes",
  "instance": "/api/v1/links"
}
```

```json
// 503 — PostgreSQL unavailable (bounded by a 5 s pool timeout)
{
  "type": "about:blank",
  "title": "Service unavailable",
  "status": 503,
  "detail": "database temporarily unavailable",
  "instance": "/api/v1/links"
}
```

### Redirect

```bash
curl -i http://localhost:8080/aB3xK92
```

Expected response:

```http
HTTP/1.1 302 Found
Location: https://example.com/very/long/path
```

Error cases:

```json
// 404 — unknown code
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "link not found: aB3xK92",
  "instance": "/aB3xK92"
}
```

```json
// 410 — link existed but expired (also on cache hit; expiry is re-checked)
{
  "type": "about:blank",
  "title": "Gone",
  "status": 410,
  "detail": "link expired: aB3xK92",
  "instance": "/aB3xK92"
}
```

### Get link details

```bash
curl http://localhost:8080/api/v1/links/aB3xK92
```

Response `200 OK` — same `LinkResponse` shape as create. Error case:
`404` with the same ProblemDetail shape as the redirect's 404.

### Statistics

```bash
curl http://localhost:8080/api/v1/links/aB3xK92/stats
```

Example response:

```json
{
  "code": "aB3xK92",
  "clickCount": 42
}
```

`clickCount` is the persisted PostgreSQL count plus clicks still pending in
Redis, so it is current without waiting for the next flush. Error case:
`404` for an unknown code.

### Delete

```bash
curl -i -X DELETE http://localhost:8080/api/v1/links/aB3xK92
```

Response: `204 No Content` with an empty body; the Redis cache entry is
evicted immediately. Error case: `404` for an unknown code.

### Error response

Errors use Spring's `ProblemDetail` representation following RFC 9457.
`type`, `title`, `status`, `detail` and `instance` are always present.

## 🧪 Testing

The test suite uses Testcontainers to run real PostgreSQL and Redis instances. Docker is the only infrastructure dependency required for the full integration test suite.

```bash
./gradlew test
```

### Test coverage

- **Unit tests**
  - Base62 encoding/decoding
  - URL validation
  - domain-level rules

- **Integration tests**
  - create → redirect → stats
  - PostgreSQL persistence
  - Redis cache interaction (hit/miss, TTL, wire-format forward compatibility)
  - expiration behavior
  - deletion and cache invalidation

- **Failure injection (live, via Testcontainers)**
  - Redis outage → graceful degradation, counters survive and recover
  - PostgreSQL outage → fast `503` on create/miss, cache hits keep serving `302`, flush failure restores counters

- **Error scenarios**
  - unknown code → `404`
  - expired link → `410`
  - taken custom alias → `409`
  - oversized body → `413`
  - database outage → `503`

- **Cache behavior**
  - repeated redirect requests hit Redis
  - PostgreSQL is not queried on cache hits

- **Counter behavior**
  - redirect increments Redis counter
  - scheduled flush persists clicks to PostgreSQL
  - atomic Lua reset avoids lost increments

- **Concurrency**
  - 10,000 parallel clicks
  - final persisted count must equal the number of successful redirects

Monitoring is also covered by a dedicated smoke/integration validation path:
the application exposes metrics, Prometheus scrapes them, recording rules are
evaluated, and Grafana provisioning is verified from a clean state. The full
monitoring stack does not need to run for every unit test.

CI runs the unit suites (`*Test`) and the full Testcontainers build as
separate jobs, plus validation of Prometheus configuration/rules, Grafana
provisioning/dashboard JSON, and a Trivy scan of the application image
(`.github/workflows/ci.yml`).

## ⚙️ Configuration

The following environment variables are used by the application.

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | Application HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/urlshortener` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `postgres` | PostgreSQL user |
| `DB_PASSWORD` | `postgres` | PostgreSQL password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_TIMEOUT` | `2s` | Redis command timeout |
| `SHORTENER_CODE_LENGTH` | `7` | Generated Base62 code length (1–11) |
| `SHORTENER_URL_MAX_LENGTH` | `2048` | Maximum accepted original URL length |
| `SHORTENER_ALIAS_MAX_LENGTH` | `64` | Maximum custom alias length |
| `SHORTENER_MAX_COLLISION_RETRIES` | `5` | Bounded retries on generated-code collision |
| `SHORTENER_CACHE_TTL` | `1h` | Redis TTL for cached links |
| `SHORTENER_FLUSH_INTERVAL` | `PT5S` | Click counter flush interval |
| `SHORTENER_MAX_PAYLOAD_SIZE` | `16KB` | Raw request body ceiling |

All values also follow Spring relaxed binding — e.g. `SPRING_DATASOURCE_URL`
overrides `DB_URL` the same way `spring.datasource.url` would.

Prometheus and Grafana operational configuration is intentionally file-based
under `monitoring/` rather than represented as application environment
variables. Secrets, including Grafana administrative credentials for
environments that require them, must be injected through environment variables
or the deployment secrets mechanism rather than stored in Git.

### Virtual threads

Virtual threads are enabled using Spring Boot's standard property:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

The application should still be designed around non-blocking resource usage where practical. Virtual threads make blocking request handling cheaper, but they do not remove database, network, or connection-pool limits.

## 🧠 Design Notes

### Why `302` instead of `301`?

A `301` response can be cached by clients and intermediaries, which may cause subsequent requests to bypass the service and therefore bypass server-side click counting.

A `302` response is a better fit when every redirect should normally reach the application. This does not guarantee that every client will contact the service on every visit, but it avoids the permanent-cache semantics of `301`.

### Atomic counter flush

A naive implementation such as:

```text
HGET clicks:{code}
DEL clicks:{code}
```

can lose increments that arrive between the two operations.

The implementation therefore uses a Lua script so the read-and-reset operation runs atomically inside Redis.

### Code generation under concurrency

The application may first check whether a generated code already exists, but this check is only an optimization.

The database must enforce uniqueness:

```text
application check
      +
database UNIQUE constraint
      +
retry on collision
```

This combination makes code generation safe under concurrent requests.

### Expired links in Redis

The cached value contains the original URL and `expiresAt`.

On every cache hit the application checks the expiration timestamp. This prevents a link from remaining usable merely because its Redis cache entry has a longer TTL than the business-level link expiration.

### Cache invalidation

When a link is deleted, its Redis entry must be evicted immediately.

For updates to link metadata, the cache must either be refreshed with the new value or explicitly invalidated before the next read.

## 🔐 Error Model

The API exposes a consistent error contract using `ProblemDetail`.

Recommended application-level error categories include:

| Situation | HTTP status |
|---|---:|
| Invalid request / URL | `400` |
| Short code or alias already exists | `409` |
| Link does not exist | `404` |
| Link exists but has expired | `410` |
| Request body over the size limit | `413` |
| Database temporarily unavailable | `503` |
| Unexpected server failure | `500` |

The exact `type`, `title`, and `detail` values should remain stable enough for clients to consume reliably.

## 📊 Observability

Sprint 13 provides the application-level observability foundation with
Actuator, health indicators and Micrometer business metrics. Sprint 29 extends
that foundation into a complete Prometheus + Grafana metrics pipeline.

### Metrics pipeline

```text
Application metrics
      ↓
Micrometer
      ↓
Spring Boot Actuator
      ↓
/actuator/prometheus
      ↓
Prometheus
      ↓
Recording / alert rules
      ↓
Grafana dashboards
```

### Metrics contract

The minimum business metric set (see
[`docs/observability-contract.md`](docs/observability-contract.md) for the full
contract, including timers and platform metrics) is:

```text
urlshorter_linksCreated_total
urlshorter_linksRedirected_total
urlshorter_linksNotFound_total
urlshorter_linksExpired_total
urlshorter_linksDeleted_total

urlshorter_cacheHit_total
urlshorter_cacheMiss_total

urlshorter_clicksIncremented_total
urlshorter_clicksIncrementFailed_total
urlshorter_clicksFlushed_total
urlshorter_clicksFlushFailed_total

urlshorter_redirectDuration_seconds
urlshorter_postgresLookupDuration_seconds
urlshorter_redisLookupDuration_seconds
```

The Prometheus exposition may contain additional JVM, process, Spring and
infrastructure metrics supplied by the Micrometer registry.

Metrics use only low-cardinality dimensions such as application/service and
environment. User-specific or request-specific values such as usernames,
aliases, original URLs, request IDs and short-link codes must not be used as
metric labels.

### Configuration as code

All monitoring configuration is versioned in Git:

```text
monitoring/
├── prometheus/
│   ├── prometheus.yml
│   └── rules/
│       ├── recording-rules.yml
│       └── alert-rules.yml
└── grafana/
    ├── dashboards/
    │   └── url-shortener-overview.json
    └── provisioning/
        ├── datasources/
        │   └── prometheus.yml
        └── dashboards/
            └── dashboards.yml
```

Manual setup through the Prometheus or Grafana UI is not part of the target
workflow. A clean environment must recreate the datasource, dashboard,
recording rules and alert rules from the repository.

### Dashboard coverage

The main URL Shortener dashboard covers:

- service traffic and request rate;
- redirect, `404` and `410` activity;
- p50/p95/p99 HTTP latency;
- Redis cache hit/miss and failure signals;
- click increments, flush rate, failures and pending backlog;
- PostgreSQL lookup, connection-pool and error signals;
- JVM/process CPU, memory, GC, threads and uptime.

### Operational alerts

Alert rules are stored in Git and should cover the operational conditions
defined by the project baseline and failure scenarios, including:

```text
application target unavailable
elevated HTTP error rate
elevated redirect latency
Redis/cache failures
continued click-flush failures
stuck pending flush backlog
PostgreSQL availability/connectivity problems
```

Alert thresholds are based on the measured performance baseline from Sprint 23
and the failure/operational behaviour validated in Sprints 17–18 rather than
being arbitrary dashboard values.

### Validation

The monitoring stack has its own quality gates:

```text
prometheus.yml
recording-rules.yml
alert-rules.yml
Grafana provisioning YAML
dashboard JSON
```

must all pass automated validation in CI.

At least one automated smoke/integration scenario verifies the complete path:

```text
request
  → application metric changes
  → /actuator/prometheus
  → Prometheus scrape
  → PromQL / recording rule
  → Grafana query
```

The monitoring endpoints and dashboards must not expose secrets or
user-provided sensitive values.


## 🐳 Docker

The target local environment is started with:

```bash
docker compose up --build
```

Recommended containers:

```text
url-shortener-app
postgres
redis
prometheus
grafana
```

Prometheus is connected to the application through the Docker network and
scrapes `/actuator/prometheus`. Grafana receives its Prometheus datasource and
dashboard definitions through provisioning files in `monitoring/grafana/`.

The monitoring stack is intended to be disposable and reproducible: deleting
the Grafana/Prometheus containers and starting the Compose stack again must
recreate the configured datasource, dashboards and scrape/rule configuration
from Git.

The development compose file may omit the application container:

```bash
docker compose -f docker-compose.dev.yml up -d
```

## 🔄 End-to-End Example

```text
1. POST /api/v1/links
   └─> PostgreSQL INSERT
       └─> 201 Created

2. GET /aB3xK92
   └─> Redis HIT
       └─> Redis INCR
           └─> 302 Redirect

3. GET /aB3xK92
   └─> Redis HIT
       └─> Redis INCR
           └─> 302 Redirect

4. Every 5 seconds
   └─> Lua GET+DEL (atomic)
       └─> PostgreSQL batch UPDATE

5. GET /api/v1/links/aB3xK92/stats
   └─> PostgreSQL count + pending Redis counter

6. Monitoring
   └─> Micrometer
       └─> /actuator/prometheus
           └─> Prometheus scrape
               └─> recording / alert rules
                   └─> Grafana dashboard
```

## 🗺 Roadmap

- [ ] Click analytics: user agent, referrer, country/device metadata
- [ ] Daily click statistics and aggregation
- [ ] Rate limiting for link creation: the `RateLimiter` port exists with a
      no-op default — replace the bean with a Bucket4j + Redis implementation
- [ ] Authentication and "my links"
- [ ] QR code generation
- [ ] GraalVM native image startup/memory comparison
- [x] ~~k6 load tests for redirect throughput~~ — done (Sprint 23, see *Performance baseline*)
- [x] ~~Benchmark cache-hit vs cache-miss latency~~ — done (Sprint 23/24)
- [ ] Distributed deployment and horizontal scaling
- [x] ~~Metrics dashboard with Prometheus + Grafana~~ — done (Sprint 29, configuration and dashboards as code)

### Sprint 29 milestone — Metrics platform

The production-oriented roadmap now includes a reproducible monitoring stack:

```text
Spring Boot / Micrometer
        ↓
Actuator /actuator/prometheus
        ↓
Prometheus
├── recording rules
└── alert rules
        ↓
Grafana
├── datasource provisioning
└── dashboard provisioning
```

The target is **metrics pipeline as code**: monitoring configuration is
versioned, validated in CI, and restored automatically from Git without manual
UI configuration.

## 📸 Screenshots

Add project screenshots here once they are stored inside the repository, for example:

```text
docs/
└── screenshots/
    └── swagger-ui.png
```

Then reference them with normal Markdown:

```markdown
![Swagger UI](docs/screenshots/swagger-ui.png)
```

Keeping screenshots inside the repository is preferable to linking to temporary external pages.

## 📄 License

MIT — see [`LICENSE`](LICENSE).

## 📋 Changelog

Release notes and the release verification record:
[`CHANGELOG.md`](CHANGELOG.md).

## 👤 Project Status

This repository is intended as a production-oriented pet project demonstrating:

- Spring Boot application design
- Redis cache-aside architecture
- atomic Redis operations with Lua
- PostgreSQL persistence
- scheduled counter aggregation
- virtual-thread based request handling
- integration testing with Testcontainers
- API documentation and operational endpoints
- Prometheus metrics export and scrape-based monitoring
- Grafana dashboards with datasource/dashboard provisioning as code
- Prometheus recording and alert rules stored in Git
- CI validation of monitoring configuration and dashboard artifacts
