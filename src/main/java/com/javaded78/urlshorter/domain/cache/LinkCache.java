package com.javaded78.urlshorter.domain.cache;

import java.util.Optional;

/**
 * Cache-aside port for link lookup (Task 6.2). The service layer talks to Redis only through this
 * abstraction — never through Redis command details.
 *
 * <p>Stampede policy (Task 6.6): concurrent misses are allowed to issue duplicate DB queries for
 * now — no distributed locks, no request coalescing until a proven need. Entries carry {@code
 * expiresAt} and the caller re-validates expiration on every hit.
 */
public interface LinkCache {

  /**
   * Returns the cached link, or empty on miss. An entry that fails to deserialize is evicted and
   * reported as a miss.
   */
  Optional<CachedLink> get(String code);

  /**
   * Caches the link with {@code min(configuredTtl, remainingLifetime)}; already-expired links are
   * not cached at all (Task 6.4).
   */
  void put(CachedLink link);

  /** Removes the entry — explicit invalidation on delete/update. */
  void evict(String code);
}
