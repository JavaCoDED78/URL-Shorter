# Changelog

## 1.1.2 — Create-endpoint rate limiter (Sprint 20.4)

A real, Redis-backed, key-scoped fixed-window rate limiter for
`POST /api/v1/links` replaces the port-only `NoOpRateLimiter`.

- New `RateLimiter` beans bound by `RateLimitConfig`: `RedisRateLimiter`
  (when enabled) and `NoOpRateLimiter` (the disabled-by-default admit-
  everything default). Exactly one is registered per process.
- `LinkService.create` probes the limiter first (`RateLimiter.tryAcquire`);
  a denied probe short-circuits before any validation or DB work.
- New `RateLimitExceededException` → `429 Too Many Requests` with
  `retryAfter: 1` (`RateLimiter` / `ErrorType.RATE_LIMITED`), handled by
  `GlobalExceptionHandler`.
- New `shortener.rate-limiter` config (`RateLimitProperties`,
  `@ConfigurationProperties`): `enabled` (default `false`), `window`
  (`PT1M`), `max-requests` (`100`), driven by
  `SHORTENER_RATE_LIMITER_*` env vars. Disabled by default so the existing
  high-volume suites (which fire hundreds of concurrent creates) stay green.
- Fail-open: a Redis outage admits the request (availability-wins, like
  Tasks 17.1/17.2) and does not count as a denial.
- New counter `urlshorter.rateLimiterDenied`
  (`urlshorter_rateLimiterDenied_total`); verified end-to-end by
  `RedisRateLimiterIT`.

## 1.1.1 — Centralized error types (2026-10-06)

The `type` field in RFC 9457 `ProblemDetail` responses was previously
`about:blank#<name>`, a `about:blank` URI with a `#` fragment — not a valid,
self-describing error identifier. Error categories were also scattered across
`GlobalExceptionHandler`, so the contract was easy to drift out of sync.

- Introduced `ErrorType` enum (`api/error/ErrorType.java`) as the single source
  of truth for every error category. Each category is now an absolute URI
  (`https://urlshortener.javaded78.dev/errors/{code}`, e.g.
  `.../errors/validation-error`, `.../errors/link-not-found`), readable by both
  clients and humans.
- `GlobalExceptionHandler` now references `ErrorType.uri()`/`title()` for every
  handler.
- **Breaking**: `type` values changed from `about:blank#<name>` to the new
  absolute URIs. Clients that branch on `type` must update their expectations.

## 1.1.0 — Metrics platform (Sprint 29, 2026-10-01)

Prometheus + Grafana monitoring pipeline as code. Sprint history and per-task
evidence live in `DEVELOPMENT_PLAN.md` (Sprint 29); decisions are recorded in
`docs/adr/ADR-007-prometheus-grafana-monitoring-stack.md` and
`docs/adr/ADR-008-monitoring-configuration-as-code.md`.

### Features

- Prometheus exposition via `/actuator/prometheus` served by the standard
  Spring Boot Actuator `PrometheusScrapeEndpoint` (auto-configured by
  `spring-boot-micrometer-metrics`, exposure via
  `management.endpoints.web.exposure.include`). The earlier
  `PrometheusEndpointController` workaround (based on a misdiagnosed
  "missing auto-configuration") was removed — the actuator endpoint was
  already active and is the canonical implementation
- Prometheus scrape config, recording rules (25) and alert rules (13) as code
  under `monitoring/prometheus/`
- Grafana datasource + dashboard provisioning as code under
  `monitoring/grafana/` — dashboard `url-shortener-overview` (24 panels),
  no manual UI setup, clean-volume reproduction verified
- Metrics contract finalized in `docs/observability-contract.md`
  (`urlshorter.*` namespace, 11 counters + 3 timers, low-cardinality labels
  only)
- CI: monitoring config validation (`validate-monitoring-config.py`) and a
  full-stack monitoring smoke job (`validate-monitoring-stack.py`), Trivy scan
  retained
- Production ingress policy for the monitoring stack
  (`docs/production-ingress-policy.md`)
- Benchmark harness fixes and Sprint 23 baseline re-measurement
  (2026-10-01): genuine PostgreSQL-miss scenario (`shared-iterations`),
  resilient `prepare.py` (ephemeral-port retries, idempotent re-runs);
- `GET /bench-hit` (k6 hit-path harness) moved out of the production
  controller behind `shortener.benchmark.enabled` (default `false`, enabled
  in the dev compose stack); `bench-hit` added to reserved aliases so no
  link can claim the route when the endpoint is off
  README/DEVELOPMENT_PLAN baseline tables re-measured with the fixed
  harness — full profile 4 322 597 requests / 18 010 rps avg / p95
  18.96 ms / 0 failures

## 1.0.0 — Production readiness milestone (Sprints 1–28, 2026-09-29)

First release-candidate state. Full sprint history with per-sprint evidence
lives in `DEVELOPMENT_PLAN.md` (all 28 sprints checked off); design
decisions are recorded as ADRs in `docs/adr/`.

### Features

- URL shortening with uniform-random Base62 codes (default 7 chars,
  `SHORTENER_CODE_LENGTH`, case-insensitive lookup)
- Custom aliases with reserved-word protection
- Business expiration (`expiresAt`) enforced on every read, including
  Redis cache hits (`410 Gone` after expiry)
- Cache-aside redirects with a forward-compatible JSON wire format
- Click buffering in Redis (atomic `INCR`) flushed every 5 s by a Lua
  atomic drain (`GET`+`DEL` in one step), persisted as one batched
  `unnest` UPDATE; stats = persisted + pending (no flush lag visible)
- Graceful degradation: PostgreSQL outage → bounded `503` (5 s pool
  timeout) with Redis cache hits still serving `302`; Redis outage →
  cache pass-through to PostgreSQL, availability preserved
- Request size limit (413), RFC 9457 `ProblemDetail` errors, OpenAPI 3 /
  Swagger UI, virtual threads, actuator health + metrics

### Performance baseline (release measurement, 2026-09-29, dev stack)

| Scenario | Requests | p50 | p95 | p99 | Checks |
|---|---:|---:|---:|---:|---:|
| Redirect, Redis hit | 118 450 | 8.7 ms | 12.6 ms | 15.1 ms | 99.95 % |
| Redirect, PostgreSQL miss | 57 230 | 9.2 ms | 13.9 ms | 18.6 ms | 99.94 % |
| Create | 36 654 (~1 220 rps) | 22.9 ms | 31.7 ms | 38.8 ms | 99.97 % |
| 10 000-VU spike redirects | 11 623 | 5.5 ms | 53.3 ms | 124.4 ms | 99.90 % |

Residual check failures (≤ 0.1 %) are connection-level during VU ramp, not
application errors; latency figures come from successful requests.

### Known limitations

- **Redis-outage redirect latency ~6 s**: each of the three Redis ops on
  the redirect path (cache get, counter increment, cache write-back) waits
  out the 2 s command timeout. Availability is preserved (302), but a
  Redis outage slows uncached redirects. Mitigation path: circuit breaker
  on the cache/counter ports.
- Click loss window: clicks younger than one flush interval (default 5 s)
  are lost if Redis itself crashes.
- Stats `clickCount` is read-consistent, not transactional across
  persisted + pending sources.

### Verification at release

- `./gradlew clean build` green (unit + Testcontainers integration suite,
  incl. 10 000-client concurrency and live failure injection)
- Docker image `url-shorter:release`: Trivy **0 CRITICAL**; runs as
  non-root `app`; compose smoke (create 201 / redirect 302 / OpenAPI 200)
  green; no secrets in logs
- Full functional + consistency + failure walk-through against the live
  stack recorded in the Sprint 28 report
