# Changelog

## Unreleased

### CacheAsideIT TTL assertions made jitter-aware (removes flakiness)

`CacheTtlPolicy` applies a symmetric `±15%` jitter to the written cache TTL (`base·(1+u)`,
`u ~ Uniform(-0.15, 0.15)`), so the entry's live TTL is a range, not a fixed value. Two `CacheAsideIT`
integration assertions still checked narrow bounds and became flaky:

- `neverExpiringLinkGetsConfiguredTtl` asserted `> 3500s` for a never-expiring link. Base is the
  configured 1h (3600s), so jitter lands it in `[3060, 4140]s`; values below 3500 (~40% of runs)
  failed the assertion. Now `isBetween(3000L, 4200L)`.
- `expiringLinkGetsBoundedTtl` asserted `isBetween(1L, 30L)` for a link with ~30s remaining. Base is
  the remaining lifetime (~30s), so jitter spreads it to `[25.5, 34.5]s`; any positive jitter wrote
  past 30s (~50% of runs). Now `isBetween(20L, 36L)`.

Bounds keep the intent (configured-TTL applied / TTL bounded by remaining life) while absorbing the
jitter window plus a little slack for test execution time. Verified: 12/12 stress runs green
(previously each method failed ~40-50%).

### DNS semaphore in `UrlValidator` is now instance-scoped, the ceiling lives in `UrlConfig`

The concurrent-lookup guard was `private static final Semaphore dnsPermits = new Semaphore(8)`
held as a class constant in {@link com.javaded78.urlshorter.domain.validation.UrlValidator}. A
static semaphore is shared across the whole classloader, so integration suites that spin up
multiple Spring contexts (or run with parallel forks) share one ceiling — the per-context limits
mix, and the value is not configurable.

- **Change.** The semaphore is now an instance field (`private final Semaphore dnsPermits`), built
  from `UrlConfig#dnsSemaphorePermits` in the constructor. {@code UrlConfig} gains that component,
  bridged from {@code ValidationConfig#DEFAULT_DNS_SEMAPHORE_PERMITS} (8) — the same pattern as
  {@code maxLength}/{@code allowedSchemes}, so the domain record carries the value instead of the
  validator hard-coding it.
- **Consequences.** Each context gets its own semaphore, so parallel/multi-context suites no longer
  share a limit. The default (8) is unchanged; it deliberately sits below the Hikari max pool size
  (10). The default is defined in the Spring config layer ({@code ValidationConfig}) rather than as
  a new bound {@code shortener.*} property, so no external config is invented — it is an
  instance-configurable record field, not an operator-tunable property.
- **Verification.** {@code UrlValidatorTest}, {@code WriteLinkServiceTest} updated for the new
  {@code UrlConfig} arity; {@code unitTest} green, {@code spotlessCheck} green.

### Spotless: normalize Java formatting (Google Java Format 1.22.0) across the codebase

The `com.diffplug.spotless` gate (`googleJavaFormat` + `importOrder` + `removeUnusedImports` +
whitespace) was red: 45 files had drifted from the configured format (import ordering, Javadoc
reflow, multi-line annotation collapsing, `record` formatting). Ran `spotlessApply`, which touched
only whitespace, import ordering and Javadoc reflow — no logic changed. `spotlessCheck` is green and
`:compileJava` builds clean.

### Cache TTL now carries symmetric jitter (staggered expiry, stampede avoidance)

The cache TTL was `min(configured TTL, remaining lifetime)` with a single fixed
`shortener.cache.ttl` (1 h by default). A mass of links created together (an import, a campaign)
therefore shared one base TTL and expired in the same second — a periodic DB stampede. The
singleflight coalescer (`RedisLinkCache#coalesce`, ADR-011) smooths that stampede but does not remove
it: a burst of expiries still lands inside a narrow window.

- **Change.** `CacheTtlPolicy` is now an instance holding a `jitterFraction`; it applies a symmetric
  `±jitterFraction` about the base TTL (`base * (1 + u)`, `u ~ Uniform(-jitterFraction, jitterFraction)`)
  via `ThreadLocalRandom`. The base computation (`min`, remaining lifetime, already-expired → not
  cached) is unchanged; only the written TTL is spread. Jitter is a new `shortener.cache.jitter`
  property (`SHORTENER_CACHE_JITTER`, default `0.15`, i.e. ±15%), validated `@PositiveOrZero
  @DecimalMax("0.5")`. The `Cache` record is a canonical 5-arg record; it has **no** secondary
  4-arg constructor — a secondary constructor breaks Spring's
  {@code @ConfigurationProperties} canonical-constructor bind, which would null the earlier
  components and NPE at startup.
- **Consequences.** Distinct links now expire across a ±15 % window instead of in lockstep, so the
  per-interval stampede drops from N concurrent misses to a smooth trickle. Cost: a never-expiring
  link can live up to 15 % longer (and a short-lived one up to 15 % shorter than its remaining life) —
  well inside the stale-while-correct window, since expiry is re-checked from the payload on every hit.
- **Verification.** `CacheTtlPolicyTest` (jitter bounds over 10 k draws, clamp to ±50 %, jitter on the
  remaining lifetime), `ShortenerPropertiesValidationTest` (negative / over-0.5 jitter flagged), and
  `RedisLinkCacheTest` (jitter disabled, exact configured-TTL assertions) are green.

### `existsByCode` pre-check is now case-insensitive (decoupled from the alias validator)

The read path (`findByCodeIgnoreCase`) already compared `lower(l.code) = lower(:code)` — served by
the V2 `lower(code)` functional unique index — but the create-path pre-check `existsByCode` was a
case-sensitive derived query (`WHERE code = ?`). The two were only kept in sync by
`AliasValidator.validateAndNormalize` lowercasing the alias before the check: an implicit,
untested contract. Change or remove that normalization and mixed-case aliases (`AbC` vs `abc`)
would pass the pre-check and surface only as a unique-violation on insert.

- **Fix.** `LinkJpaRepository#existsByCode` is now an explicit
  `@Query("SELECT COUNT(l) > 0 ... WHERE lower(l.code) = lower(:code)")`, mirroring
  `findByCodeIgnoreCase` so it is served by the same `lower(code)` index and is case-insensitive
  regardless of the caller's casing. The port Javadoc now documents that the pre-check does not
  depend on the caller lowercasing first.
- **Verification.** New `LinkJpaRepositoryTest#existsByCodeIsCaseInsensitive` stores a mixed-case
  code and asserts `existsByCode` matches under any case. It was verified RED on the old derived
  query (`AssertionFailedError at LinkRepositoryTest.java:80`) and GREEN after the fix.
  `LinkCreateFlowIT`, `WriteLinkServiceTest`, `AliasValidatorTest` remain green.

### Delete now evicts the cache **after** the transaction commits (closes the stale-entry race)

`WriteLinkService.delete` issued its second cache evict *inside* the `@Transactional` method body —
i.e. before the transaction committed. Until the commit the deleted row is still visible to
concurrent readers, so a redirect racing the delete could read the still-committed row and
re-populate the cache *between* that second evict and the real commit; the stale entry then
outlived the row until the next cache read.

- **Fix.** The second evict now runs in an `afterCommit` `TransactionSynchronization` registered
  through `TransactionSynchronizationManager.registerSynchronization(...)` in `WriteLinkService#delete`,
  so it fires only after the row is globally gone — ordered after any racing reader's write-back. A
  plain in-transaction evict cannot close the `[evict → commit]` window, because the Redis delete is
  outside the DB transaction; `afterCommit` is the only ordering that guarantees the eviction is
  visible after the deletion.
- **Verification.** `WriteLinkServiceTest#deleteEvictsCacheAfterCommitNotBefore` statically mocks
  `TransactionSynchronizationManager`, asserts the second evict does **not** fire synchronously before
  commit, captures the registered hook, and asserts the evict fires only when the commit callback
  runs — a deterministic distinguisher that fails `TooManyActualInvocations` on the old code.
  `ConcurrencyIT#deleteVersusRedirectRaceConverges` still asserts end-to-end convergence.

### Persistence timestamps now driven by the injected `Clock` (single source of truth)
The `LinkEntity` previously stamped `created_at` and `updated_at` from `Instant.now()`
inside `@PrePersist`/`@PreUpdate`. That broke the project's `Clock` discipline — every
other seam threads an injected `Clock`, so those two entity callbacks were the only places
still calling `Instant.now()` — and it was a **second source of truth** for `created_at`:
`Link.created(...)` receives `createdAt` from the write path, but `JpaLinkRepository.toEntity()`
mapped only `code`, `originalUrl` and `expiresAt`, so `@PrePersist` silently overwrote the
domain-supplied instant.

- **`created_at` is now mapped explicitly** from the domain: `JpaLinkRepository.toEntity()`
  passes `link.createdAt()` into the new 4-arg `LinkEntity` constructor, so the persistence
  layer no longer overwrites it with `Instant.now()`.
- **`updated_at` is owned by Spring Data JPA auditing** (`@EntityListeners(AuditingEntityListener)`
  + `@LastModifiedDate`) instead of a hand-rolled `@PreUpdate` callback.
- **`JpaAuditingConfig`** registers `@EnableJpaAuditing` and a `DateTimeProvider` that
  delegates to the same `Clock` bean `UrlShorterApplication` already provides, so production
  and tests stamp timestamps through one clock.
- The persistence-only **`@DataJpaTest` slice** (`LinkJpaRepositoryTest`) imports
  `JpaAuditingTestConfiguration` and enables auditing, so it exercises the real
  audit path (clock → `DateTimeProvider` → `@LastModifiedDate`) rather than any fallback.

Verified: `./gradlew compileJava` clean (no new warnings); `./gradlew test` green —
258/258.

### Singleflight negative caching on the redirect path (Tasks 21.1/21.2)

The redirect was a plain cache-aside with no stampede protection and no negative
caching: an evicted hot key let `N` concurrent misses hit PostgreSQL, and a scan
of nonexistent codes re-read the database on every request.

- **Singleflight.** `RedisLinkCache#coalesce` now coalesces concurrent misses for
  the same code behind one in-flight lookup using a `do { get → putIfAbsent }
  while (!owner)` loop over a `ConcurrentMap` of bare `CompletableFuture`s —
  whoever installs the future is the sole owner and runs the `miss` supplier
  once, everyone else joins it. `computeIfAbsent` is deliberately avoided: its
  Javadoc permits the mapping to run more than once under contention, which would
  spawn several owners and several DB queries (the very stampede). The owner
  writes the positive cache *before* completing the shared future and removing
  the slot, so a caller that arrives after the remove reads the value back
  instead of becoming a second owner.
- **Negative caching.** `coalesce` short-circuits Bloom-absent codes and codes
  with a short-TTL negative marker before reaching PostgreSQL, so a cold scan of
  nonexistent codes never touches the database. The Bloom filter (`CodeBloom-
  Filter`) is maintained incrementally per replica via `markExists(code)` on the
  create path (`WriteLinkService`), and a delete drops the stale marker via
  `evict`.
- **Config.** New `shortener.cache` properties drive the policy
  (`shortener.cache.ttl`, `negative-ttl`, `expected-codes`,
  `bloom-false-positive-rate`); the negative marker TTL is 30 s by default.
- **Verification.** `RedisLinkCacheTest#coalesceCoalescesConcurrentMissesInto-
  SingleLookup` asserts one DB query under 50 concurrent callers; the full suite
  is green (305 tests, 0 failures).
- **Multi-replica scaling.** Per-instance singleflight + Bloom is the accepted default; the
  N-replica fan-out (cold miss of a hot key → N PostgreSQL lookups) and cross-replica Bloom
  absence are documented in
  `docs/adr/ADR-011-per-instance-singleflight-bloom.md`.

### Distributed click flush (ShedLock)

The `@Scheduled` click flush (`ClickFlushJob#flushClicks`) previously ran on a
plain `@Scheduled` with no coordination. In a multi-pod deployment every pod
drained the same Redis counters every interval: the Lua drain is atomic per key
so no increments were double-counted, but each replica still paid the full
drain→persist→restore cost and a crash during the PostgreSQL write could race
across replicas. The whole job is now serialized with ShedLock (JDBC provider,
`shedlock` table in PostgreSQL — migration V3): `@SchedulerLock(name =
"clickFlush")` wraps `flushClicks()`, `@EnableSchedulerLock` enables it on the
entry point, and a losing instance skips the drain entirely.

- **V3** creates the `shedlock` table (`name` VARCHAR(64) PK, `lock_until`,
  `locked_at`, `locked_by` VARCHAR(255)).
- **ShedLockConfig** builds the `LockProvider` from `JdbcTemplateLockProvider`
  (`withTableName("shedlock")`, `usingDbTime`, `withLockedByValue` from
  `shortener.shedlock.locked-by:instance`).
- **`@EnableSchedulerLock(defaultLockAtMostFor = "PT5S")`** on the entry point
  so a crash that holds the lock cannot pin it forever.
- **`SchedulerLockIT`** asserts acquire when the table is free and skip when an
  instance holds a future lock (`PT1H` interval keeps the real scheduler off).

Verified: `./gradlew test` green; `defaultLockAtMostFor` attribute validated by
`compileJava`.

### Transaction boundaries moved to the use-case (service) layer
Previously the only `@Transactional` annotations lived on two `LinkJpaRepository`
methods — Spring Data's `deleteByCode` and `batchIncrementClickCounts`. That
opened a transaction around a single statement and let the rest of the write
path run outside one: `WriteLinkService.delete`'s `findByCodeIgnoreCase → evict
→ deleteByCode` sequence and `create`'s collision-retry loop were not a single
use-case transaction, so a concurrent create with the same (reused) code/alias
or an in-flight redirect could wedge between the find and the delete. The rule
is now enforced: transaction boundaries live on the service layer, and the
Spring Data repository is a persistence-only port that carries no
`@Transactional`.

- **`WriteLinkService.delete`** is annotated `@Transactional` — the
  find→evict→discard→delete sequence is one transaction. It is a normal
  (non-self) call from `LinkController`, so the Spring proxy still intercepts
  it and the transaction is real.
- **`WriteLinkService.create` has no transaction** — validation + rate-limit
  run *outside* any transaction on purpose. `create` delegates the short-lived
  DB insert to the separate **`doCreate`**, which also has no `@Transactional`.
  Each `LinkRepository.save` uses `saveAndFlush`, so the unique index is
  enforced per attempt and a collision surfaces as `DataIntegrityViolationException`
  inside a bounded collision-retry loop — not at an unpredictable commit point.
  `doCreate` is `public` so tests can drive the write path directly. The split
  exists because URL validation does a **blocking DNS lookup**; holding a
  pooled PostgreSQL connection during that lookup starved the pool under the
  300-way `ConcurrencyIT` (only ~10 of 300 concurrent creates survived before
  the 5 s connection timeout). Keeping the blocking lookup and the fast insert
  on different code paths — no connection held between them — is what fixes it.
- **`CounterFlushService.flush`** remains `@Transactional` — the
  drain→update→restore round-trip is atomic.
- **`LinkJpaRepository`** lost both `@Transactional` annotations. The methods
  still carry `@Modifying(clearAutomatically, flushAutomatically)`, which
  controls persistence-context behaviour (flush before the next write, clear
  after the statement) but does **not** open a transaction. This removes the
  confusing dual `@Transactional` (interface + service) that made it unclear
  where a transaction actually begins.
- Docs updated to reflect that `LinkEntity`, JPQL, `@Modifying` and the port
  boundary live in `infra.persistence`, while `@Transactional` lives only on
  the write/delete path — never on the blocking-create code path.

Verified: `WriteLinkServiceTest`, `ReadLinkServiceTest`,
`CounterFlushServiceTest`, `PostgresFailureIT` (incl. transaction-begin 503),
and `ConcurrencyIT` (10 k parallel redirects, 300 concurrent creates with all
surviving, alias race, delete-vs-redirect convergence) pass; `./gradlew
compileJava` clean.

## 1.2.0 — Pure domain repository port and infrastructure separation (2026-10-08)

Clean-architecture reorganization that removes the JPA/Spring Data layer from
the domain. The `domain.repository.LinkRepository` was previously the Spring
Data interface (the concrete JPA boundary leaking into the domain, against the
repository's own rule that infra stays out of the domain).

- **New pure port**: `domain.repository.LinkRepository` is now a domain
  interface only (`findByCodeIgnoreCase`, `existsByCode`, `count()`, `save(Link)`,
  `deleteByCode`, `batchIncrementClicks(Map)`). It knows nothing about Spring
  Data, JPA, or `LinkEntity`.
- **New infra split** in `infra.persistence`:
  - `LinkJpaRepository` — the Spring Data JPA slice for `LinkEntity` (JPQL /
    native statements, `@Transactional` boundaries).
  - `JpaLinkRepository` — the adapter implementing the port over
    `LinkJpaRepository`, with the single `LinkEntity ↔ Link` mapping. The
    `save()` semantic keeps an immediate flush so a unique-constraint collision
    surfaces in `WriteLinkService`'s retry loop.
- **Code generation moved out of the domain**: `Base62Codec` and
  `Base62CodeGenerator` now live in `infra.encoding` and implement the still-domain
  `CodeGenerator` port. Tests migrated to `infra.encoding`.
- **Shared expiration value object**: `domain.model.ExpirationPolicy`
  centralizes the `isExpired` business rule so `Link` and `CachedLink` cannot
  diverge; `Link.created(...)` now takes an explicit `createdAt` for
  deterministic tests under the injected `Clock`.
- **Port API change**: `WriteLinkService.save()` returns `void` (previously
  the entity) and `batchIncrementClicks` takes a `Map<String, Long>`. Tests
  updated accordingly (unit + `ConcurrencyIT`, `PostgresFailureIT`,
  `ObservabilityIT`, `ClickFlushIT`).

Result: the dependency rule is enforced at the boundary — Spring Data, JPQL and
`@Transactional` now live exclusively in `infra.persistence`, and the domain
depends only on the `Link` model and its ports. `./gradlew test`: 250/250
green; `./gradlew clean build` green; `spotlessCheck` clean.

## 1.1.3 — Read/write split and sealed error hierarchy

A set of structural changes to the request-handling core: the monolithic
`LinkService` is split into separate write and read layers, time is injected
through `Clock` instead of calling `Instant.now()` in app/infra seams, and the
expected-error hierarchy becomes `sealed` so the exception→HTTP mapping is
exhaustive by construction.

- **CQRS-lite split (`LinkService` → `WriteLinkService` / `ReadLinkService`)**:
  the write path (`create`, `delete` — validation, rate-limit, collision
  retries, transactional inserts, cache/counter eviction) is separated from the
  hot read path (`resolve`/redirect, `getByCode`, `getStats` — cache-aside,
  click increment, pending-counter stats). Each path is tested on its own
  (`WriteLinkServiceTest`, `ReadLinkServiceTest`); `LinkServiceTest` is removed.
  This decouples the hot redirect path from the write path's rate-limit probe.
- **Injected `Clock`**: `WriteLinkService`, `ReadLinkService`, and
  `RedisLinkCache` take a `Clock` instead of calling `Instant.now()` directly,
  so `create` and the `expiresAt` re-checks are deterministic in tests.
  `UrlShorterApplication` registers a `Clock.systemUTC()` bean.
- **`sealed` error hierarchy**: `ShortenerException` is `sealed` with four
  permitted subtypes (`LinkNotFoundException`, `LinkExpiredException`,
  `AliasAlreadyExistsException`, `RateLimitExceededException`, all `final`), and
  `GlobalExceptionHandler.handleShortenerException` maps them in a single
  pattern-matching `switch` — exhaustive by construction, so a new subtype
  without a mapping is a compile error rather than a silent 500.

## 1.1.2 — Create-endpoint rate limiter (Sprint 20.4)

A real, Redis-backed, key-scoped fixed-window rate limiter for
`POST /api/v1/links` replaces the port-only `NoOpRateLimiter`.

- New `RateLimiter` beans bound by `RateLimitConfig`: `RedisRateLimiter`
  (when enabled) and `NoOpRateLimiter` (the disabled-by-default admit-
  everything default). Exactly one is registered per process.
- `WriteLinkService.create` probes the limiter first (`RateLimiter.tryAcquire`);
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
