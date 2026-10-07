# Architecture Decision Records

> Architecture Decision Records for the URL Shortener service. All ADRs are **Accepted** — the corresponding implementation has shipped and passed integration tests.

## Index

| #   | ADR | Decision | Status | Date | Sprint | Category |
|-----|-----|----------|--------|------|--------|----------|
| 001 | [ADR-001](ADR-001-302-instead-of-301.md) | `302 Found` instead of `301 Moved Permanently` for redirects | Accepted | 2026-09 | Sprint 1 | API Design |
| 002 | [ADR-002](ADR-002-redis-cache-aside.md) | Cache-aside link lookup in Redis | Accepted | 2026-09 | Sprint 6 | Performance |
| 003 | [ADR-003](ADR-003-redis-click-buffering.md) | Buffer click counts in Redis, persist asynchronously | Accepted | 2026-09 | Sprint 7/8 | Performance |
| 004 | [ADR-004](ADR-004-lua-atomic-flush.md) | Atomic counter drain via a Lua script | Accepted | 2026-09 | Sprint 8 | Resilience |
| 005 | [ADR-005](ADR-005-base62-code-generation.md) | Uniform-random Base62 code generation with DB-unique guarantee | Accepted | 2026-09 | Sprint 3 | Persistence |
| 006 | [ADR-006](ADR-006-expiration-semantics.md) | Business expiration carried in the cached value, re-checked on every read | Accepted | 2026-09 | Sprint 6 | Resilience |
| 007 | [ADR-007](ADR-007-prometheus-grafana-monitoring-stack.md) | Prometheus + Grafana monitoring stack | Accepted | 2026-10 | Sprint 29 | Observability |
| 008 | [ADR-008](ADR-008-monitoring-configuration-as-code.md) | Monitoring configuration as code | Accepted | 2026-10 | Sprint 29 | Observability |
| 009 | [ADR-009](ADR-009-create-rate-limiter.md) | Redis-backed fixed-window rate limiting on the create endpoint | Accepted | 2026-10 | Sprint 20.4 | Resilience |

## Records

### [ADR-001](ADR-001-302-instead-of-301.md) — `302 Found` instead of `301 Moved Permanently` for redirects

> The redirect endpoint answers **`302 Found`** with the destination in the `Location` header.

**Why:** `301` is cached by design and silently bypasses click counting. Every bypassed request is a lost click and a stale statistic.

### [ADR-002](ADR-002-redis-cache-aside.md) — Cache-aside link lookup in Redis

> Cache-aside with Redis: key `link:{lowercaseCode}` holds a JSON document (`code`, `originalUrl`, `expiresAt`) with TTL.

**Why:** One PostgreSQL lookup per redirect is wasteful for hot links. Redis hits cost ~5 090 rps vs ~3 210 rps misses (Sprint 23).

### [ADR-003](ADR-003-redis-click-buffering.md) — Buffer click counts in Redis, persist asynchronously

> Redirects increment `clicks:{code}` with atomic `INCR`. A scheduled job (default 5s) drains and persists via batch `unnest` UPDATE.

**Why:** Inline PostgreSQL writes on the hottest read path cause hot-row contention. Measured ~4× faster than per-click updates at 10 000 clicks.

### [ADR-004](ADR-004-lua-atomic-flush.md) — Atomic counter drain via a Lua script

> The drain runs as a Redis Lua script: `GET` + `DEL` execute atomically, so increments can only land before or after the drain.

**Why:** `GET` + `DEL` as two separate commands loses increments arriving between them. Lua is Redis's own transaction mechanism — no external locking.

### [ADR-005](ADR-005-base62-code-generation.md) — Uniform-random Base62 code generation with DB-unique guarantee

> Codes are drawn uniformly from `[62^(length-1), 62^length)` range. The database `UNIQUE` constraint is the only uniqueness guarantee.

**Why:** Application-level check is not safe under concurrency. DB constraint arbitrates collision; configurable retries (default 5) handle the edge case.

### [ADR-006](ADR-006-expiration-semantics.md) — Business expiration carried in the cached value, re-checked on every read

> The cached value carries `expiresAt`. On every cache hit, the service re-validates the timestamp and answers `410 Gone`.

**Why:** If expiry relied on Redis TTL alone, a cached entry could outlive the business expiration and keep serving redirects — a correctness bug.

### [ADR-007](ADR-007-prometheus-grafana-monitoring-stack.md) — Prometheus + Grafana monitoring stack

> Prometheus scrapes `/actuator/prometheus` every 15s; Grafana reads from the Prometheus datasource with dashboard provisioning from Git.

**Why:** Pull-based scraping matches the microservice model; PromQL is the de facto standard; zero transformation between Micrometer and Prometheus.

### [ADR-008](ADR-008-monitoring-configuration-as-code.md) — Monitoring configuration as code

> All monitoring configuration is versioned in Git under `monitoring/`. No manual configuration through Prometheus or Grafana UI.

**Why:** Manual configuration is not reproducible, not reviewable, lost on restart, and has no audit trail. CI gates invalid configuration (broken PromQL or YAML).

### [ADR-009](ADR-009-create-rate-limiter.md) — Redis-backed fixed-window rate limiting on the create endpoint

> The create endpoint is protected by a key-scoped, fixed-window Redis limiter (`ratelimit:{key}`, `INCR` per probe, `EXPIRE` on the first probe of a window). The bean is selected by `shortener.rate-limiter.enabled`: `RedisRateLimiter` when enabled, `NoOpRateLimiter` (admits everything) otherwise.

**Why:** The unauthenticated, cheap `POST /api/v1/links` is the natural abuse target (alias squatting, enumeration, insert spam). A limiter behind the `RateLimiter` port is a drop-in bean swap — no call-site change — and fails open on a Redis outage so it can't take down link creation.
