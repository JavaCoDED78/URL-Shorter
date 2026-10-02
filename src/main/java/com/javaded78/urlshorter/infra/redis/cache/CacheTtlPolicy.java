package com.javaded78.urlshorter.infra.redis.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * TTL calculation for cached links (Task 6.4): {@code min(configuredCacheTTL,
 * remainingLinkLifetime)}. Never-expiring links get the configured TTL; already-expired links must
 * not be cached.
 */
public final class CacheTtlPolicy {

  private CacheTtlPolicy() {}

  /**
   * @return the TTL to use, or empty when the link is already expired and must not be written to
   *     the cache
   */
  public static Optional<Duration> ttlFor(Instant expiresAt, Instant now, Duration configuredTtl) {
    if (expiresAt == null) {
      return Optional.of(configuredTtl);
    }
    if (!expiresAt.isAfter(now)) {
      return Optional.empty();
    }
    Duration remaining = Duration.between(now, expiresAt);
    return Optional.of(remaining.compareTo(configuredTtl) < 0 ? remaining : configuredTtl);
  }
}
