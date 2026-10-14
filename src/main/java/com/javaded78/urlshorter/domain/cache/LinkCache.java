package com.javaded78.urlshorter.domain.cache;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Cache-aside port for link lookup (ADR-002). The service layer talks to Redis only through this
 * abstraction — never through Redis command details.
 *
 * <p>Stampede protection (ADR-011): {@link #coalesce} coalesces concurrent misses for the same code
 * behind a single in-flight lookup, so an evicted/expired hot key can no longer fan N concurrent
 * PostgreSQL reads into one. Negative caching (ADR-011): {@link #coalesce} short- circuits the
 * database for codes the Bloom filter has declared absent, so a scan of nonexistent codes never
 * reaches PostgreSQL.
 *
 * <p>Entries carry {@code expiresAt} and the caller re-validates expiration on every hit.
 *
 * <p>Every Redis read/write degrades to a miss or no-op on a {@code DataAccessException}, never
 * letting the exception escape — the redirect itself must survive a dead Redis.
 */
public interface LinkCache {

  /**
   * Returns the cached link, or empty on miss. An entry that fails to deserialize is evicted and
   * reported as a miss.
   */
  Optional<CachedLink> get(String code);

  /**
   * Resolves a code with stampede protection and negative caching (ADR-011):
   *
   * <pre>
   *   Bloom filter absent  → miss (known non-existent): do NOT run `miss` — skip PostgreSQL
   *   Bloom present + Redis positive hit → return the cached link
   *   Bloom present + Redis miss       → run `miss` AT MOST ONCE across concurrent callers
   *                                      (singleflight) and cache whatever it returns
   * </pre>
   *
   * <p>{@code miss} performs the PostgreSQL lookup and must return {@link Optional#empty()} when
   * the code does not exist (the implementation then records the miss so subsequent lookups short-
   * circuit) or throw {@code LinkNotFoundException}/{@code LinkExpiredException} when the link is
   * gone. The supplier is coalesced: concurrent callers for the same code share a single run.
   *
   * @param code the (case-insensitive) link code
   * @param miss the PostgreSQL-backed resolver, run at most once per miss window
   * @return the cached link on a hit, or empty when the code is known absent
   */
  Optional<CachedLink> coalesce(String code, Supplier<Optional<CachedLink>> miss);

  /**
   * Caches the link with {@code min(configuredTtl, remainingLifetime)}; already-expired links are
   * not cached at all (ADR-011). A miss (empty result) is recorded as a negative cache entry with
   * the configured negative TTL.
   */
  void put(CachedLink link);

  /** Removes the entry — explicit invalidation on delete/update. */
  void evict(String code);

  /**
   * Records that {@code code} now exists (ADR-011), so the Bloom filter no longer short-circuits a
   * redirect for it. Called by the write path after a successful create; also evicts any stale
   * negative marker for the code so the freshly created link resolves against PostgreSQL.
   *
   * @param code the (case-insensitive) link code
   */
  void markExists(String code);
}
