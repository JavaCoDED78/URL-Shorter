# Changelog

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
