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

A **client-scoped, fixed-window, Redis-backed** limiter protects the create
endpoint behind the existing `RateLimiter` port, selected by configuration:

```text
shortener.rate-limiter.enabled=true   →  RedisRateLimiter (RedisRateLimiter)
shortener.rate-limiter.enabled=false  →  NoOpRateLimiter (admits everything)
```

The two beans are mutually exclusive (`@ConditionalOnProperty`), so exactly one
`RateLimiter` is bound per process. Call sites probe a per-client
`RateLimitScope` (`WriteLinkService.create` →
`rateLimiter.tryAcquire(new RateLimitScope("create", rateLimitSource.resolveClientKey()))`)
— never a bare `"create"` string.

### How it works

- One Redis key per client scope, `ratelimit:{key}` (the create probe uses
  `key = <client IP>`), holding a plain integer. Every probe `INCR`s it; `INCR`
  atomically creates the key and returns `1` on the first probe of a window.
- The first probe of a window arms a TTL (`EXPIRE`, duration = `window`,
  default `PT1M`) on the freshly-created key. Redis self-deletes it when the
  window elapses, so the next probe `INCR`s from `1` again — the window is owned
  entirely by Redis, with no clock arithmetic or stale-window bookkeeping.
- Admission is `count <= maxRequests` (default `100`); the probe after the
  limit is denied. Admission is a single counter read — no per-request
  PostgreSQL lookup.

### Per-client scoping (Task 20.4 hardening)

The limiter must **never** be keyed on a constant scope — a single global
`ratelimit:create` counter means one abuser exhausts the budget for every
client, or the limiter becomes useless. The probe key is the caller's IP,
resolved by `CreateRateLimitSource` (the `CreateIpRateLimitSource` bean), so
each client has its own counter.

- When `X-Forwarded-For` is present and the immediate peer is a **trusted
  proxy** (the explicit allowlist `shortener.rate-limiter.trusted-proxies`,
  e.g. `10.0.0.0/8`), the key is the **first hop** of that chain — the real
  client behind the proxy stack.
- Otherwise the header is attacker-controlled and the key falls back to
  `HttpServletRequest#getRemoteAddr()`, so a forged XFF can only masquerade as
  the local proxy's address.
- `CreateRateLimitSource` is bound unconditionally (`@Bean`): `WriteLinkService`
  needs it whether or not the limiter is enabled, and it is harmless when the
  active limiter is the no-op.

### Failure policy

A Redis outage is **fail-open** or **fail-closed** as configured by
`shortener.rate-limiter.fail-open` (default `true`). On a Redis failure the
outcome is decided by policy, so a transient outage neither floods the endpoint
(fail-open) nor blocks every client (fail-closed). Either way the request is not
counted as a denial and `rateLimiterDenied` is not incremented. The default
fail-open matches the availability-wins stance of the PostgreSQL/Redis
degradation paths (Tasks 17.1/17.2): the limiter is a best-effort defence, not a
correctness guarantee.

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
`SHORTENER_RATE_LIMITER_WINDOW` (`PT1M`), `SHORTENER_RATE_LIMITER_MAX`
(`100`), `SHORTENER_RATE_LIMITER_FAIL_OPEN` (`true`), and
`SHORTENER_RATE_LIMITER_TRUSTED_PROXIES` (empty = use `remoteAddr`). The
default `enabled: false` keeps existing high-volume suites (which fire hundreds
of concurrent creates, e.g. `ConcurrencyIT`) unaffected. `fail-open: true`
defaults to the availability-wins stance; set it `false` in a strictly
authenticated or regulated deployment to treat a broken limiter as a refusal.

### Per-client scoping — the key security property

The limiter is keyed by the caller's IP, never by a constant scope. This is the
core defence against the limiter itself becoming useless: a single global
counter lets one abuser either block every client or render the ceiling
meaningless. `CreateRateLimitSource` resolves the per-client key from `X-Forwarded-For`
(when the peer is a trusted proxy) or `remoteAddr` (otherwise).

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
