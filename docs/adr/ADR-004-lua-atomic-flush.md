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

- processes a whole **batch** of discovered keys per execution — keys are
  found by non-blocking `SCAN` (count 100), never `KEYS`, never an
  unbounded key list; one script call drains up to `BATCH_SIZE`
  (default 1000) counters, so the drain is `O(number of batches)`
  round-trips instead of `O(number of counters)` and stops scaling with
  the number of hot links;
- returns only the drained counters as a flat `[key, value, ...]` array
  (missing keys are absent), so it is idempotent — safe to re-run;
- executes atomically: a single batch is all-or-nothing, so if it fails
  neither the read nor the delete happened and those counters stay
  intact (already-drained earlier batches in the same cycle are returned
  to the caller, preserving the per-key fail-open policy).

Flush failure after a successful drain (PostgreSQL down) restores the
drained values to Redis and surfaces `clicks.flush.failed`; recovery after
the database returns persists everything without loss (failure-injection
tested).

## Consequences

- Zero lost increments from the drain itself; the only accepted loss
  window is a Redis crash inside one flush interval (ADR-003).
- Lua is Redis's own transaction mechanism — no external locking, no
  WATCH/MULTI round-trips, no retry logic in the application. Each batch
  is one atomic script; cross-instance serialization of the whole job is
  separate (ShedLock, below).
- A single instance always drains, but across replicas the drain runs once per
  cycle, not once per replica. ShedLock serializes the job on a `shedlock` row
  in PostgreSQL (`@SchedulerLock(name = "clickFlush")` on
  `ClickFlushJob#flushClicks`, see `SchedulerLockIT`); a loser skips the drain
  entirely instead of re-running the same drain→restore.

## Alternatives considered

- `GET` + `DEL` as two commands — rejected: loses increments, the exact
  bug this ADR exists to prevent.
- `WATCH`/`MULTI`/`EXEC` optimistic transaction — workable but requires a
  retry loop on conflict and moves complexity into the client; the Lua
  script expresses the invariant where it belongs (server side).
