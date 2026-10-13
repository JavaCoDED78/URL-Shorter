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
Cache-aside   INCR
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
- **Redirect**: Redis lookup → on hit, check `expiresAt` from the cached value, `INCR` the click counter, `302`; on miss, read PostgreSQL, check expiration, write-through to Redis, then redirect.
- **Click flush**: every `SHORTENER_FLUSH_INTERVAL` (default 5s), a Redis Lua script atomically reads-and-resets each `clicks:{code}` counter, then a batch `UPDATE` persists the drained values to PostgreSQL. Never implement this as separate `HGET` + `DEL` calls — increments arriving between the two would be lost.

## 4. Project structure

```
src/main/java/com/javaded78/urlshorter/
├── api/
│   ├── controller/       # REST endpoints
│   ├── dto/              # request/response DTOs
│   └── error/            # ProblemDetail / exception handling
├── domain/
│   ├── model/            # domain objects (no Redis-specific fields here)
│   ├── cache/            # cache port: CachedLink (stable wire format) + LinkCache
│   ├── counter/          # counter port: ClickCounter (no Redis types in signatures)
│   ├── repository/       # pure persistence port (domain interface; no Spring Data/JPA)
│   ├── config/           # pure domain config records (WriteServiceConfig, UrlConfig, AliasConfig; bridged from ShortenerProperties)
│   └── service/          # application/domain services
│   ├── exception/        # @ControllerAdvice-driven errors (sealed hierarchy)
│   └── validation/       # input validators (AliasValidator, UrlValidator)
├── infra/
│   ├── encoding/         # Base62Codec + Base62CodeGenerator (implements domain.service.CodeGenerator)
│   ├── persistence/      # JPA entity + LinkRepository implementation (JpaLinkRepository over LinkJpaRepository)
│   └── redis/
│       ├── cache/        # Redis cache implementation (LinkCache: get/put/evict)
│       ├── counter/      # click counters
│       ├── script/       # Lua scripts
│       ├── flush/        # ClickFlushJob (scheduler) + CounterFlushService (flush orchestrator)
│       └── limiter/      # rate limiter (Redis-backed fixed window)
└── config/               # properties, filters, OpenAPI, startup diagnostics

monitoring/               # repo-root Prometheus/Grafana configuration as code
├── prometheus/           # prometheus.yml + recording/alert rules
└── grafana/              # dashboards + datasource/dashboard provisioning
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
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/urlshortener` | PostgreSQL JDBC URL (standard Spring Boot property) |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | PostgreSQL user |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | PostgreSQL password (only consumed inside Docker Compose) |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_TIMEOUT` | `2s` | Redis command timeout |
| `SHORTENER_CODE_LENGTH` | `7` | Generated Base62 code length |
| `SHORTENER_URL_MAX_LENGTH` | `2048` | Maximum accepted original URL length |
| `SHORTENER_ALIAS_MAX_LENGTH` | `64` | Maximum custom alias length |
| `SHORTENER_MAX_COLLISION_RETRIES` | `5` | Bounded retries on generated-code collision |
| `SHORTENER_CACHE_TTL` | `1h` | Redis TTL for cached links |
| `SHORTENER_CACHE_JITTER` | `0.15` | Symmetric TTL jitter (±) about the base cache TTL, in `[0, 0.5]` (default ±15%). Spreads out the expiry of a mass of links created together (imports, campaigns) so they do not all expire in the same second and stampede PostgreSQL; `0` disables it |
| `SHORTENER_FLUSH_INTERVAL` | `PT5S` | Click counter flush interval |
| `SHORTENER_MAX_PAYLOAD_SIZE` | `16KB` | Raw request body ceiling |
| `SHORTENER_BENCHMARK_ENABLED` | `false` (`true` in the dev compose stack) | Enables the benchmark-only `GET /bench-hit` redirect used by the k6 harness |
| `SHORTENER_MAX_URI_LENGTH` | `1024` | Maximum request-URI length (characters); oversized paths answered `413` by `RequestUriLengthFilter` before any handler runs |

All values also follow Spring relaxed binding — e.g. `SPRING_DATASOURCE_URL`
maps to `spring.datasource.url`. Postgres credentials are passed to the
application exclusively through `SPRING_DATASOURCE_*`; the `DB_*` names above
are only consumed by the Docker Compose stack to point the container at the
Postgres service. Prometheus and Grafana configuration is file-based under
`monitoring/`, not env-var based.

## 8. API surface

| Method | Path | Description | Codes |
|---|---|---|---|
| `POST` | `/api/v1/links` | Create a short link | `201`, `400`, `409` |
| `GET` | `/{code}` | Redirect to the original URL | `302`, `404`, `410` |
| `GET` | `/api/v1/links/{code}` | Get link details | `200`, `404` |
| `GET` | `/api/v1/links/{code}/stats` | Get click statistics | `200`, `404` |
| `DELETE` | `/api/v1/links/{code}` | Delete a link | `204`, `404` |

Errors use Spring's `ProblemDetail` (RFC 9457): `type`, `title`, `status`, `detail`, `instance`. Keep `type`/`title`/`detail` stable enough for clients to rely on. Error categories: `400` invalid request/URL, `409` code/alias already taken, `404` link not found, `410` link expired, `413` request body over the size limit, `503` database temporarily unavailable, `500` unexpected failure.

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
2. **Click loss after a failed PostgreSQL update** post-drain — closed: a failed flush restores the drained counters in Redis instead of losing clicks (Tasks 8.6/17.3, `RedisFailureIT`).
3. **Cache serving an expired link** — always re-check `expiresAt` on cache hit, don't trust Redis TTL alone.
4. **Code collisions** — the DB unique constraint is the real guarantee, not the app-level existence check.
5. **Connection pool exhaustion** — virtual threads increase concurrency, but the PostgreSQL pool size is still the limit.
6. **Stale cache after delete** — both PostgreSQL and Redis must be updated together. Closed: the second cache evict now runs in an `afterCommit` `TransactionSynchronization` (registered via `TransactionSynchronizationManager` in `WriteLinkService#delete`), so it fires only after the row is globally gone — ordered after any racing reader's write-back. A plain in-transaction evict leaves the `[evict → commit]` window open.
7. **Stats lag** — PostgreSQL-only stats can undercount due to pending Redis clicks; the API semantics for this need to be explicit and documented.

## 11. Testing expectations

- Integration tests run against **real** PostgreSQL and Redis via Testcontainers — Docker is the only infrastructure dependency for `./gradlew test`.
- Coverage should include: unit tests (Base62 codec, URL validation, domain rules), integration tests (create → redirect → stats, cache interaction, expiration, delete + cache invalidation), error scenarios (`404`/`409`/`410`), cache behavior (repeated redirects hit Redis, no DB query on a cache hit), counter behavior (Redis increment, scheduled flush, atomic Lua reset), and concurrency (10,000 parallel clicks — the persisted count must equal the number of successful redirects).

## 12. Roadmap / not yet built

Done since this list was written: k6 load tests + cache-hit/miss latency
benchmarks (Sprint 23), Prometheus + Grafana metrics pipeline as code
(Sprint 29, see `monitoring/` and `docs/adr/ADR-007`/`ADR-008`).

Still open:

- Click analytics (user agent, referrer, device), daily click aggregation
- Rate limiting beyond the create endpoint: the `RateLimiter` port now has a
  Redis-backed fixed-window implementation (Task 20.4, gated by
  `shortener.rate-limiter.enabled`); a Bucket4j token-bucket variant would be a
  drop-in bean swap behind the same port
- Authentication + "my links"
- QR code generation
- GraalVM native image comparison
- Horizontal scaling
- Production deployment hardening beyond `docs/production-ingress-policy.md`

## 13. Reference documents

- `README.md` — user-facing overview, quick start, API examples.
- `DEVELOPMENT_PLAN.md` — full sprint-by-sprint checklist (checkboxes), priorities (P0–P3), milestones (M1–M6), task-sizing guidance, and the risk register in more detail.
