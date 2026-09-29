# ADR-005: Uniform-random Base62 code generation with DB-unique guarantee

- Status: **Accepted**
- Date: 2026-09 (Sprint 3, hardened in later sprints)

## Context

Short codes must be short, unique, URL-safe, and cheap to generate under
concurrency. The naive approach — random candidate plus an application-level
"does it exist?" check — is not safe: two concurrent requests can both pass
the check and both attempt to insert.

## Decision

- Codes are drawn **uniformly** from the whole
  `[62^(length-1), 62^length)` range and encoded with a Base62 codec, so
  every code has exactly `length` characters and the full keyspace is used
  (no leading-zero bias, no accidentally short codes).
- The database `UNIQUE` constraint on `code` is the **only** uniqueness
  guarantee. A bounded number of retries on collision (default 5,
  configurable) turns a conflict into a fresh candidate.
- `SecureRandom` is the source; length is configurable
  (`SHORTENER_CODE_LENGTH`, default 7; hard cap 11 = what fits in a
  `long`).
- Custom aliases go through the same insert path — the same constraint and
  the same `409` contract.

## Consequences

- Correct under any concurrency the database can serve — the constraint,
  not the application, arbitrates.
- Collision probability is negligible at this keyspace (birthday bound:
  ~`n²/2·62⁷` for `n` codes), so retries almost never fire.
- The generator is stateless and thread-safe (immutable configuration,
  thread-safe `SecureRandom`).

## Alternatives considered

- Auto-increment integer ids encoded as Base62 — rejected: leaks creation
  volume and ordering, invites enumeration of every link.
- Application-level uniqueness check as the guarantee — rejected: a
  check-then-act race; it remains only as a collision-retry mechanism.
- UUIDs — rejected: 36 characters is not "short"; Base62 of 128 bits is
  still 22 characters and buys nothing here.
