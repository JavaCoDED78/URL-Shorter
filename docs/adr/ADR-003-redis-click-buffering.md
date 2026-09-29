# ADR-003: Buffer click counts in Redis, persist asynchronously

- Status: **Accepted**
- Date: 2026-09 (Sprint 7/8)

## Context

Every redirect increments a click count. Writing to PostgreSQL inline would
put a write on the hottest read path — with 10 000 concurrent clicks that
is 10 000 row updates per burst, contention on hot rows, and write latency
added to every redirect.

## Decision

- Redirects increment a Redis counter `clicks:{lowercaseCode}` with atomic
  `INCR` — O(1), no contention semantics to speak of.
- A scheduled job (default every 5 s, `SHORTENER_FLUSH_INTERVAL`) drains
  the counters and persists the deltas in one batched `unnest` UPDATE.
- Counters carry **no TTL**: a counter must survive until the flush
  persists it. The drain deletes the key only after a successful read;
  a failed PostgreSQL write restores the value to Redis so no click is
  lost (verified by failure-injection tests).
- Stats report persisted count + pending Redis counter, so the API is
  current without waiting for the flush.

## Consequences

- Redirect latency is decoupled from PostgreSQL write latency.
- Stats lag at most one flush interval by construction; the API masks even
  that by reading the pending counter.
- Crash window: clicks less than one interval old can be lost if Redis
  itself dies — bounded and accepted (default 5 s).
- One scheduled write burst per interval instead of per-click writes —
  measured ~4× faster than per-click updates at 10 000 clicks.

## Alternatives considered

- Inline PostgreSQL `UPDATE` per click — rejected: write on the read path,
  hot-row contention, unnecessary latency.
- Kafka click events — rejected for the current scale: the outbox/consumer
  machinery is unjustified while a bounded Redis buffer plus batch flush
  meets the requirements.
