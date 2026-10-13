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

### Residual crash window (accepted, MVP)

The drain and the DB commit are two independent stores, so the
drain→commit transition cannot be made atomic without a durable ledger or
2PC. The residual window is the single bulk `UPDATE` between `drainAll()`
(Redis counters now zeroed) and its commit:

- the `UPDATE` **committed** — the persisted count already includes the
  drained clicks, Redis is empty, nothing is lost;
- the `UPDATE` **did not commit** (crash inside the one DB round-trip) —
  Redis is empty and the database update rolled back, so the drained
  clicks are lost. The current design closes the *same-cycle* case by
  restoring the drained values to Redis (`INCRBY`) on a failed write, but
  a crash in that window still loses them.

This is accepted by design: click counting is best-effort analytics
(17.2 — the redirect is the product, the click counter is not). The
window is one in-process write per cycle; recovering drained counters
across a process death is a durable-outbox concern deferred to a proven
need (see below).

## Alternatives considered

- `GET` + `DEL` as two commands — rejected: loses increments, the exact
  bug this ADR exists to prevent.
- `WATCH`/`MULTI`/`EXEC` optimistic transaction — workable but requires a
  retry loop on conflict and moves complexity into the client; the Lua
  script expresses the invariant where it belongs (server side).
- Drain into a staging key (rename `clicks:*` → `clicks:staging:*`) and
  restore from staging on next startup — rejected: a rename is not
  batch-atomic (a crash mid-rename leaves a partial mix across both
  namespaces that needs reconciliation), and the DB `UPDATE` is
  additive (`clicks = clicks + N`), so replaying staging on startup is
  not idempotent — a crash after the DB commit but before the staging
  key is deleted double-counts. This trades the tiny in-memory restore
  window for a larger, non-idempotent replay window plus reconciliation
  code; it is neutral, not free.
- Durable outbox (a `click_flush_outbox` ledger replayed idempotently on
  startup) — deferred until a proven need. This is the only path that
  actually closes the cross-system window (durable idempotent ledger),
  and would replace the in-memory restore here.
