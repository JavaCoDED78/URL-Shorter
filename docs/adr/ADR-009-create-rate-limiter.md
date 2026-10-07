# ADR-009: Redis-backed fixed-window rate limiting on the create endpoint

- Status: **Accepted**
- Date: 2026-10 (Sprint 20.4)

## Context

The create endpoint (`POST /api/v1/links`) is unauthenticated and cheap, so it
is the natural target for abuse — alias squatting, enumeration, and load that
wastes PostgreSQL inserts. A `RateLimiter` port and a `NoOpRateLimiter` default
were added in Sprint 20 to hold the abstraction, but the port was never wired to
a working implementation: nothing rejected an abusive create before it reached
the database.

## Decision

A **key-scoped, fixed-window, Redis-backed** limiter protects the create
endpoint behind the existing `RateLimiter` port, selected by configuration:

```text
shortener.rate-limiter.enabled=true   →  RedisRateLimiter (RedisRateLimiter)
shortener.rate-limiter.enabled=false  →  NoOpRateLimiter (admits everything)
```

The two beans are mutually exclusive (`@ConditionalOnProperty`), so exactly one
`RateLimiter` is bound per process and call sites (`LinkService.create` →
`rateLimiter.tryAcquire("create")`) never change.

### How it works

- One Redis key per scope, `ratelimit:{key}` (the create probe uses
  `key = "create"`), holding a plain integer. Every probe `INCR`s it; `INCR`
  atomically creates the key and returns `1` on the first probe of a window.
- The first probe of a window arms a TTL (`EXPIRE`, duration = `window`,
  default `PT1M`) on the freshly-created key. Redis self-deletes it when the
  window elapses, so the next probe `INCR`s from `1` again — the window is owned
  entirely by Redis, with no clock arithmetic or stale-window bookkeeping.
- Admission is `count <= maxRequests` (default `100`); the probe after the
  limit is denied. Admission is a single counter read — no per-request
  PostgreSQL lookup.

### Failure policy

A Redis outage is **fail-open**: the create request is admitted and the
`rateLimiterDenied` counter is not incremented. This matches the availability-
wins stance of the PostgreSQL/Redis degradation paths (Tasks 17.1/17.2); the
limiter is a best-effort defence, not a correctness guarantee. A Redis outage is
deliberately not counted as a denial.

### Response and observability

- A denied probe answers `429 Too Many Requests`
  (`ErrorType.RATE_LIMITED`, `retryAfter: 1`), handled by
  `GlobalExceptionHandler` before any validation or database work.
- `MetricsRecorder.rateLimiterDenied()` (counter
  `urlshorter.rateLimiterDenied_total`) increments only on an actual denial of
  the enabled limiter.

### Configuration (`RateLimitProperties`)

Bound via `@ConfigurationProperties(prefix = "shortener.rate-limiter")` and
overridable with `SHORTENER_RATE_LIMITER_ENABLED` (`false`),
`SHORTENER_RATE_LIMITER_WINDOW` (`PT1M`), and `SHORTENER_RATE_LIMITER_MAX`
(`100`). The default `enabled: false` keeps existing high-volume suites (which
fire hundreds of concurrent creates, e.g. `ConcurrencyIT`) unaffected.

## Consequences

- **Pro:** The create endpoint is now protected by a real limiter, but behind a
  port — swapping in a token-bucket (`Bucket4j`) or a different policy is a
  drop-in bean replacement with no call-site change.
- **Pro:** Fail-open means a transient Redis outage cannot take down link
  creation — the same operational stance used for the redirect path.
- **Pro:** Disabled by default, so production opt-in is a single property with
  no behaviour change for existing deployments.
- **Con:** A fixed window can admit `2 × maxRequests` at a boundary (the
  classic fixed-window "double burst"); the window is intentionally simple and
  self-managed by Redis. A sliding-window or token-bucket policy could be
  adopted behind the same port later if abuse warrants it.
- **Con:** The limiter is keyed by a single static scope (`"create"`) today; a
  per-tenant or per-source-IP scope would require the probe key to be
  parameterised.

## Alternatives considered

- **`Bucket4j` + Redis** — a well-supported option, but it pulls a third-party
  dependency and token-bucket semantics add tuning surface for a feature that is
  off by default. The Redis `INCR` + `EXPIRE` counter is dependency-free,
  trivial to reason about, and sufficient for the current scale.
- **Application in-memory counter** — rejected: not shared across instances and
  wrong on a multi-replica deployment. Redis is already the shared, durable-ish
  store the project leans on for coordination state.
- **Gateway/API-Gateway rate limiting** — would centralise it, but the service
  has no gateway in front of it on the dev stack; enforcing it in the port
  keeps the protection co-located with the endpoint and testable in
  `RedisRateLimiterIT`.
