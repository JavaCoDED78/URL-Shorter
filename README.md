# URL Shortener

A production-oriented URL shortening service built with **Spring Boot 4.1.1**, **Java 25**, **PostgreSQL 17**, and **Redis 7**.

The service uses PostgreSQL as the source of truth, Redis for low-latency redirects and click buffering, Flyway for schema migrations, and Testcontainers for realistic integration testing.

![CI](https://github.com/your-username/url-shortener/actions/workflows/ci.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)

## ✨ Features

- **URL shortening** with a random 7-character Base62 code.
- **Custom aliases**, for example `/my-link`.
- **Link expiration (TTL)** with `expiresAt`.
- **Cache-aside redirects**: hot links are served from Redis without a PostgreSQL query.
- **Click buffering in Redis** using atomic `HINCRBY`.
- **Atomic click flush** to PostgreSQL every 5 seconds using a Redis Lua script.
- **RFC 9457 error responses** based on Spring's `ProblemDetail`.
- **OpenAPI 3 documentation** with interactive Swagger UI.
- **Java virtual threads** enabled through a single Spring Boot property.
- **Integration testing with Testcontainers** against real PostgreSQL and Redis.
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
path, sustained cache-miss load): pool 10 → active avg 5.0, peak 10,
p95 10.5 ms; pool 16 → active avg 6.2, p95 9.6 ms. The ~1 ms gain is inside
the ±3 ms run-to-run variance and buys capacity the cache already makes
mostly unnecessary. **Pool stays at 10** (`ConnectionPoolIT` pins it) —
sized from measured concurrency, not from the virtual-thread count.

## 🏛 Architecture

```text
                              ┌─────────────────────────────┐
                              │       Spring Boot app       │
                              │      Java 25 / Web         │
                              └──────────────┬──────────────┘
                                             │
                         ┌───────────────────┴───────────────────┐
                         │                                       │
                     Cache-aside                              HINCRBY
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
HINCRBY click counter
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
Redis HINCRBY
      │
      │ every 5 seconds
      ▼
Lua script
(HGET + reset atomically)
      │
      ▼
Batch UPDATE PostgreSQL
```

The Lua script prevents the classic `HGETALL + DEL` race where increments arriving between the two commands can be lost.

## 📁 Project Structure

```text
src/main/java/com/example/shortener/
├── api/
│   ├── controller/       # REST endpoints
│   ├── dto/              # request/response DTOs
│   └── error/            # ProblemDetail / exception handling
│
├── domain/
│   ├── model/            # domain objects
│   ├── repository/       # persistence ports/repositories
│   └── service/          # application/domain services
│
├── infra/
│   └── redis/
│       ├── cache/        # Redis cache implementation
│       ├── counter/      # click counters
│       ├── script/       # Lua scripts
│       └── flush/        # scheduled flush job
│
└── config/
    └── ...               # application configuration
```

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

The application will be available at:

- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **Health:** http://localhost:8080/actuator/health

## 💻 Local Development

For local development, run PostgreSQL and Redis in Docker and the Spring Boot application from your IDE or Gradle.

```bash
docker compose -f docker-compose.dev.yml up -d

./gradlew bootRun
```

Or start the application directly from the IDE.

Flyway migrations are applied automatically during application startup.

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

Example response:

```json
{
  "code": "aB3xK92",
  "originalUrl": "https://example.com/very/long/path",
  "shortUrl": "http://localhost:8080/aB3xK92",
  "clickCount": 0,
  "expiresAt": "2026-12-31T23:59:59Z",
  "createdAt": "2026-01-15T10:15:30Z"
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

### Error response

Errors use Spring's `ProblemDetail` representation following RFC 9457.

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Short link 'aB3xK92' not found",
  "instance": "/aB3xK92"
}
```

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
  - Redis cache interaction
  - expiration behavior
  - deletion and cache invalidation

- **Error scenarios**
  - unknown code → `404`
  - expired link → `410`
  - taken custom alias → `409`

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

## ⚙️ Configuration

The following environment variables are used by the application.

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | Application HTTP port |
| `SPRING_DATASOURCE_URL` | — | PostgreSQL JDBC URL |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis host |
| `SHORTENER_CACHE_TTL` | `PT1H` | Redis TTL for cached links |
| `SHORTENER_FLUSH_INTERVAL` | `PT5S` | Click counter flush interval |
| `SHORTENER_CODE_LENGTH` | `7` | Generated Base62 code length |

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
| Unexpected server failure | `500` |

The exact `type`, `title`, and `detail` values should remain stable enough for clients to consume reliably.

## 📊 Observability

Actuator is enabled for operational endpoints.

A production deployment should expose, at minimum:

- application health
- readiness/liveness indicators
- request metrics
- Redis latency/error metrics
- PostgreSQL connection-pool metrics
- click flush success/failure metrics
- cache hit/miss metrics

Sensitive values and internal infrastructure details should not be exposed through public management endpoints.

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
```

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
       └─> Redis HINCRBY
           └─> 302 Redirect

3. GET /aB3xK92
   └─> Redis HIT
       └─> Redis HINCRBY
           └─> 302 Redirect

4. Every 5 seconds
   └─> Lua read+reset
       └─> PostgreSQL batch UPDATE

5. GET /api/v1/links/aB3xK92/stats
   └─> PostgreSQL / current persisted counter
```

## 🗺 Roadmap

- [ ] Click analytics: user agent, referrer, country/device metadata
- [ ] Daily click statistics and aggregation
- [ ] Rate limiting for link creation using Bucket4j + Redis
- [ ] Authentication and "my links"
- [ ] QR code generation
- [ ] GraalVM native image startup/memory comparison
- [ ] k6 load tests for redirect throughput
- [ ] Benchmark cache-hit vs cache-miss latency
- [ ] Distributed deployment and horizontal scaling
- [ ] Metrics dashboard with Prometheus + Grafana

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
