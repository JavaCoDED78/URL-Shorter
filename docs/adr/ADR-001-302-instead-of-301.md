# ADR-001: `302 Found` instead of `301 Moved Permanently` for redirects

- Status: **Accepted**
- Date: 2026-09 (Sprint 1)

## Context

Every redirect endpoint must choose a status code. `301 Moved Permanently`
tells clients and intermediaries that the target is permanent and may be
cached — a browser or proxy that caches it stops contacting the service for
subsequent visits. `302 Found` carries no such cacheability guarantee.

The product counts clicks server-side. Every bypassed request is a lost
click and a stale statistic.

## Decision

The redirect endpoint answers **`302 Found`** with the destination in the
`Location` header.

## Consequences

- Clients and intermediaries must re-contact the service per visit (they
  still may cache at their own discretion — `302` does not *forbid*
  caching, it just does not instruct it).
- Click counting stays server-authoritative; stats remain meaningful.
- Slightly more load on the service than a hard-cached `301` — accepted:
  the service is built to absorb exactly this load (cache-aside Redis,
  virtual threads, see ADR-002 and the performance baseline).
- Enforced by `RedirectController`; a test asserts the status is `302`.

## Alternatives considered

- `301 Moved Permanently` — rejected: cacheable by design, silently
  bypasses click counting.
- `307 Temporary Redirect` — equivalent cacheability to `302` for this
  purpose; `302` is the more widely recognised code for this use.
