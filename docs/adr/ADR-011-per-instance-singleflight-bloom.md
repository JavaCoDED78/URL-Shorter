# ADR-011: Per-instance singleflight and Bloom filter on the redirect path

- Status: **Accepted**
- Date: 2026-10 (Sprint 21.2, multi-replica review)

## Context

The redirect path shields PostgreSQL from two failure modes (Tasks 21.1/21.2):

1. **Stampede** — a batch of concurrent misses for one evicted/expired hot key would each hit
   PostgreSQL. `RedisLinkCache#coalesce` coalesces them behind a single in-flight lookup.
2. **Cold scan** — a scan of nonexistent codes must not re-read the database per request. The Bloom
   filter short-circuits codes it has declared absent.

Both mechanisms are **per-instance**:

- Singleflight relies on a per-process
  `ConcurrentMap<String, CompletableFuture<Optional<CachedLink>>> inFlight` field of
  `RedisLinkCache` (`src/main/java/com/javaded78/urlshorter/infra/redis/cache/RedisLinkCache.java`).
  Whoever `putIfAbsent`s a bare future is the sole owner and runs the `miss` supplier once; everyone
  else `join`s it.
- The Bloom membership set is an in-memory `CodeBloomFilter` (`.../cache/CodeBloomFilter.java`), a
  `@Component` constructed from `ShortenerProperties` on startup and grown incrementally by
  `markExists(code)` on the write path (`RedisLinkCache#markExists` → `WriteLinkService`).

They are deliberately in-memory and not shared, so the coalescing and short-circuiting only hold
**inside one JVM**.

## Decision

For the MVP the `inFlight` map and the in-memory Bloom filter stay **per-replica** — local to each
process, not distributed.

## Consequences

Under horizontal scaling to N replicas this has two observable effects, both correct but not
cluster-wide:

- **Singleflight does not coalesce across replicas.** A cold miss of a hot key on an evicted key
  fans out to N PostgreSQL lookups — one per replica, each running its own in-flight lookup. This is
  strictly better than no coalescing at all, but it is not the ideal "one query for the whole
  cluster".
- **The Bloom filter is not cluster-aware.** A link created on replica A is locally *absent* in
  replica B's filter, so B performs a legitimate real lookup (a DB hit) rather than a false
  negative. A freshly started replica also begins with an empty filter and rebuilds it as links are
  created on it.

Correctness is unaffected either way: PostgreSQL is the authoritative existence check. The Bloom
filter only ever answers "probably exists" (forcing a real lookup) and never "definitely does not
exist" beyond its own membership set — a false positive costs one query, and a miss caused by
cross-replica absence is a legitimate lookup, not a wrong answer.

## Alternatives considered (escape hatches, not taken for the MVP)

These are chosen only when the fan-out actually hurts — i.e. when measured DB queries per evicted
hot key under replication crosses an acceptable threshold. Until then, the per-instance versions are
simpler, dependency-free, and correct.

- **Distributed singleflight.** A Redis lock
  `SET lock:{code} <ttl> NX PX <ttl>`: the lock holder runs the lookup, the others wait and retry
  (or fall through on lock loss). Trades reduced fan-out for lock latency, lock-owner-failure
  handling (TTL + fallback to a direct lookup), and more failure modes on the hottest path in the
  system.
- **Stale-while-revalidate.** Serve a possibly-stale cached value on the miss path while
  revalidating in the background. Trades freshness for fan-out reduction; needs a freshness budget
  and a clear stale-vs-expired distinction.
- **Cluster-wide Bloom.** Periodically rebuild the filter from PostgreSQL, or move membership to a
  Redis-backed Bloom (RedisBloom / a shared bitmap). Trades an extra dependency (and its own
  consistency/eviction concerns) for cluster-wide membership, at the cost of one Redis round-trip
  per membership check and eventual staleness between rebuilds.
