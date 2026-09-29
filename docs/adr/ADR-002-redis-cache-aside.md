# ADR-002: Cache-aside link lookup in Redis

- Status: **Accepted**
- Date: 2026-09 (Sprint 6, audited Sprint 25)

## Context

The redirect path is read-heavy: one PostgreSQL lookup per redirect is
wasteful for hot links, but the database must remain the source of truth.
Options: inline caching inside the service, a separate caching proxy, or
cache-aside (application-managed cache next to the database).

## Decision

Cache-aside with Redis:

- Key `link:{lowercaseCode}` holds a JSON document
  (`code`, `originalUrl`, `expiresAt`) with TTL
  `min(configured TTL, remaining business lifetime)`.
- Lookup: Redis first; on miss read PostgreSQL, re-check expiration,
  write-through to Redis, then answer.
- Expiration is re-checked from the payload on **every** hit (ADR-006).
- Delete evicts the entry explicitly; no background invalidation jobs.
- The cache port (`LinkCache`) lives in the domain; the Redis
  implementation (`RedisLinkCache`) is infrastructure and is allowed to
  degrade to pass-through when Redis is unavailable.

## Wire format

JSON via a dedicated `ObjectMapper` — never JVM serialization. Reads are
forward-compatible: unknown fields are ignored, so a value written by a
newer service version is still serviceable until its TTL expires. Measured
alternatives (compact delimited string) saved ~48 bytes/key with zero
throughput difference and were rejected for breaking this contract.

## Consequences

- Hot redirects cost ~2 Redis ops (GET + INCR), no PostgreSQL query —
  measured ~5 090 rps for hits vs ~3 210 rps for misses (Sprint 23).
- Stampede risk on a cold cache: concurrent misses may issue duplicate DB
  queries. Accepted — no distributed locks or request coalescing until a
  proven need.
- Cache misses add a write to the read path; acceptable at this scale.

## Alternatives considered

- Read-through via a caching proxy — rejected: adds a network hop and a
  second system to reason about consistency for.
- No cache — rejected: the miss path becomes the only path and PostgreSQL
  becomes the latency floor for every redirect.
