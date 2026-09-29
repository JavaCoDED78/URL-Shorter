# ADR-006: Business expiration carried in the cached value, re-checked on every read

- Status: **Accepted**
- Date: 2026-09 (Sprint 6, contract hardened Sprint 19)

## Context

Links expire at a business timestamp (`expiresAt`). Redis entries have
their own TTL. If expiry relied on the Redis TTL alone, a cached entry
could outlive the business expiration and keep serving redirects — a
correctness bug invisible until someone hits an expired-but-cached link.

## Decision

- The cached value **carries** `expiresAt` (see ADR-002 for the wire
  format).
- On every cache hit the service re-validates the timestamp and answers
  `410 Gone` for expired links — without counting a click and without
  falling back to PostgreSQL.
- The Redis TTL is set to `min(configured TTL, remaining business
  lifetime)`, so the entry also disappears on its own; the TTL is a
  resource bound, never the correctness mechanism.
- The database row remains the record of expiration for the miss path.

## Consequences

- Expired links can never be served from cache — covered by tests that
  plant a stale entry with a future Redis TTL and assert `410`.
- Expiration semantics are identical on hit and miss paths.
- `410` is distinguishable from `404` so clients can tell "never existed /
  deleted" from "existed, now gone".

## Alternatives considered

- Trust the Redis TTL — rejected: a longer cache TTL than the remaining
  lifetime would serve expired links (the exact bug this ADR prevents).
- Background sweeper deleting expired cache entries — unnecessary given
  the TTL bound plus per-read validation.
