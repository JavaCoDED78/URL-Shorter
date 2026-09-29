# ADR-004: Atomic counter drain via a Lua script

- Status: **Accepted**
- Date: 2026-09 (Sprint 8)

## Context

Draining a counter requires reading the value and deleting the key. As two
separate commands (`GET` then `DEL`) any increment arriving in between is
lost forever: it lands after the read and its key is deleted with it. This
is the classic read-then-delete race and it sits directly on the revenue
path of the click statistics.

## Decision

The drain runs as a **Redis Lua script**: `GET` and `DEL` execute
atomically inside Redis, so increments can only land before the drain
(counted) or after it (still in the key, drained next interval). The
script:

- touches exactly one key per execution — discovered by non-blocking
  `SCAN` (count 100), never `KEYS`, never an unbounded key list;
- returns `nil` for missing keys (idempotent — safe to re-run);
- executes atomically: if anything in it fails, neither the read nor the
  delete has happened and the counters stay intact.

Flush failure after a successful drain (PostgreSQL down) restores the
drained values to Redis and surfaces `clicks.flush.failed`; recovery after
the database returns persists everything without loss (failure-injection
tested).

## Consequences

- Zero lost increments from the drain itself; the only accepted loss
  window is a Redis crash inside one flush interval (ADR-003).
- Lua is Redis's own transaction mechanism — no external locking, no
  WATCH/MULTI round-trips, no retry logic in the application.

## Alternatives considered

- `GET` + `DEL` as two commands — rejected: loses increments, the exact
  bug this ADR exists to prevent.
- `WATCH`/`MULTI`/`EXEC` optimistic transaction — workable but requires a
  retry loop on conflict and moves complexity into the client; the Lua
  script expresses the invariant where it belongs (server side).
