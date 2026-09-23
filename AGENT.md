# AGENT.md

Guidance for AI coding agents (Claude Code and similar tools) working in this repository. Read this before making changes.

## 1. What this project is

A production-oriented **URL Shortener** service. PostgreSQL is the source of truth; Redis accelerates redirects and buffers click counts before they are flushed back to PostgreSQL.

Core capabilities: create short links (random Base62 code or custom alias), TTL/expiration, cache-aside redirects, atomic click counting via Redis + Lua, RFC 9457 error responses, OpenAPI docs, virtual threads, and a Testcontainers-based integration suite.

## 2. Tech stack

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
| API docs | springdoc-openapi + Swagger UI |
| Testing | JUnit 5, Testcontainers, Awaitility, AssertJ |
| Build | Gradle Kotlin DSL |
| CI | GitHub Actions |
| Runtime | Java virtual threads (`spring.threads.virtual.enabled: true`) |

Pin exact dependency versions in Gradle; keep README badge versions in sync with the actual build configuration.

## 3. Architecture at a glance

```
Spring Boot app (API → Service → Repository)
        │
   ┌────┴─────┐
Cache-aside   HINCRBY
   │             │
Redis          Redis
link:{code}   clicks:{code}
   │             │
   │        Lua atomic flush (every 5s)
   │             │
   └────► PostgreSQL (source of truth) ◄────
              Flyway migrations
```

- **Create**: validate → generate Base62 code (or validate custom alias) → insert into PostgreSQL → `201`. The database `UNIQUE` constraint is the final defence against code collisions; an `existsByCode` pre-check is only an optimization, not a concurrency guarantee.
- **Redirect**: Redis lookup → on hit, check `expiresAt` from the cached value, `HINCRBY` the click counter, `302`; on miss, read PostgreSQL, check expiration, write-through to Redis, then redirect.
- **Click flush**: every `SHORTENER_FLUSH_INTERVAL` (default 5s), a Redis Lua script atomically reads-and-resets each `clicks:{code}` counter, then a batch `UPDATE` persists the drained values to PostgreSQL. Never implement this as separate `HGET` + `DEL` calls — increments arriving between the two would be lost.

## 4. Project structure

```
src/main/java/com/example/shortener/
├── api/
│   ├── controller/       # REST endpoints
│   ├── dto/              # request/response DTOs
│   └── error/            # ProblemDetail / exception handling
├── domain/
│   ├── model/            # domain objects (no Redis-specific fields here)
│   ├── repository/       # persistence ports/repositories
│   └── service/          # application/domain services
├── infra/
│   └── redis/
│       ├── cache/        # Redis cache implementation (LinkCache: get/put/evict)
│       ├── counter/      # click counters
│       ├── script/       # Lua scripts
│       └── flush/        # scheduled flush job
└── config/
```

The service layer must not know Redis command details — talk to Redis only through the `infra/redis` abstractions.

## 5. Commands

```bash
# Full stack (app + PostgreSQL + Redis) via Docker Compose
docker compose up --build

# Local dev: only PostgreSQL + Redis in Docker, app from the IDE/Gradle
docker compose -f docker-compose.dev.yml up -d
./gradlew bootRun

# Build / test
./gradlew clean build
./gradlew test
```

Flyway migrations run automatically on startup.
Swagger UI: `http://localhost:8080/swagger-ui.html`. Health: `http://localhost:8080/actuator/health`.

## 6. How to work on a feature (development methodology)

The project is built as a sequence of **vertical slices**, not layer-by-layer — don't implement every domain class, then every repository, then every controller. Each unit of work should leave the system in a working state, following this sprint order:

```
Infrastructure → Persistence → Create Link → Redirect → Redis Cache
→ Click Counter → Atomic Flush → Stats/Delete → Errors/Docs/Observability
→ Performance/CI/Hardening
```

Within a single feature, follow this order:

```
1. Contract → 2. Domain model → 3. Persistence/infrastructure → 4. Service
→ 5. Controller → 6. Unit tests → 7. Integration tests → 8. Observability → 9. Documentation
```

A task is **done** only when:
- [ ] it compiles
- [ ] there's an automated check wherever one makes sense
- [ ] no unexplained new compiler warnings
- [ ] configuration lives outside the code (environment variables)
- [ ] behavior is covered by a test at the right level
- [ ] logs contain no secrets
- [ ] docs are updated if needed
- [ ] the app still starts with the project's standard command

The full sprint-by-sprint breakdown, with every task as a checkbox, lives in `DEVELOPMENT_PLAN.md` — check items off there as you complete them rather than tracking progress elsewhere.

## 7. Configuration

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | Application HTTP port |
| `SPRING_DATASOURCE_URL` | — | PostgreSQL JDBC URL |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis host |
| `SHORTENER_CACHE_TTL` | `PT1H` | Redis TTL for cached links |
| `SHORTENER_FLUSH_INTERVAL` | `PT5S` | Click counter flush interval |
| `SHORTENER_CODE_LENGTH` | `7` | Generated Base62 code length |

Never hardcode connection strings or secrets — everything above must be overridable via environment variables.

## 8. API surface

| Method | Path | Description | Codes |
|---|---|---|---|
| `POST` | `/api/links` | Create a short link | `201`, `400`, `409` |
| `GET` | `/{code}` | Redirect to the original URL | `302`, `404`, `410` |
| `GET` | `/api/links/{code}` | Get link details | `200`, `404` |
| `GET` | `/api/links/{code}/stats` | Get click statistics | `200`, `404` |
| `DELETE` | `/api/links/{code}` | Delete a link | `204`, `404` |

Errors use Spring's `ProblemDetail` (RFC 9457): `type`, `title`, `status`, `detail`, `instance`. Keep `type`/`title`/`detail` stable enough for clients to rely on. Error categories: `400` invalid request/URL, `409` code/alias already taken, `404` link not found, `410` link expired, `500` unexpected failure.

## 9. Non-negotiable design rules

- **`302`, not `301`**, for the redirect — a `301` can be cached by clients/intermediaries and silently bypass server-side click counting.
- **Atomic flush only.** The Redis → PostgreSQL click flush must use a Lua script that reads and resets a counter in one atomic step. `HGET` followed by `DEL` as two separate commands can lose increments that arrive in between.
- **PostgreSQL owns uniqueness.** An `existsByCode` check is an optimization, never the concurrency guarantee — the database `UNIQUE` constraint plus retry-on-collision is what actually makes code generation safe under concurrent load.
- **Expiration is business logic, not a Redis TTL side effect.** The cached value carries `expiresAt`, and it must be checked on every read, so a link never stays usable just because its Redis entry hasn't expired yet.
- **Cache invalidation is explicit.** Deleting a link evicts its Redis entry immediately; updating a link's metadata either refreshes or invalidates the cache before the next read — never leave it stale.
- Virtual threads make blocking handlers cheap but do **not** remove PostgreSQL connection-pool limits — design around non-blocking resource usage where practical.
- Don't log secrets or credentials, including in startup diagnostics (dev/test only).

## 10. Known risks to keep closed (don't reintroduce these bugs)

1. **Lost clicks during flush** — never split the Redis drain into two non-atomic commands.
2. **Click loss after a failed PostgreSQL update** post-drain — needs a recovery path before the production milestone.
3. **Cache serving an expired link** — always re-check `expiresAt` on cache hit, don't trust Redis TTL alone.
4. **Code collisions** — the DB unique constraint is the real guarantee, not the app-level existence check.
5. **Connection pool exhaustion** — virtual threads increase concurrency, but the PostgreSQL pool size is still the limit.
6. **Stale cache after delete** — both PostgreSQL and Redis must be updated together.
7. **Stats lag** — PostgreSQL-only stats can undercount due to pending Redis clicks; the API semantics for this need to be explicit and documented.

## 11. Testing expectations

- Integration tests run against **real** PostgreSQL and Redis via Testcontainers — Docker is the only infrastructure dependency for `./gradlew test`.
- Coverage should include: unit tests (Base62 codec, URL validation, domain rules), integration tests (create → redirect → stats, cache interaction, expiration, delete + cache invalidation), error scenarios (`404`/`409`/`410`), cache behavior (repeated redirects hit Redis, no DB query on a cache hit), counter behavior (Redis increment, scheduled flush, atomic Lua reset), and concurrency (10,000 parallel clicks — the persisted count must equal the number of successful redirects).

## 12. Roadmap / not yet built

Click analytics (user agent, referrer, device), daily click aggregation, Bucket4j + Redis rate limiting, authentication + "my links", QR code generation, GraalVM native image comparison, k6 load tests, cache-hit-vs-miss latency benchmarks, horizontal scaling, Prometheus + Grafana dashboards.

## 13. Reference documents

- `README.md` — user-facing overview, quick start, API examples.
- `DEVELOPMENT_PLAN.md` — full sprint-by-sprint checklist (checkboxes), priorities (P0–P3), milestones (M1–M6), task-sizing guidance, and the risk register in more detail.
