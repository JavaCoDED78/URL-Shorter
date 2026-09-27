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
