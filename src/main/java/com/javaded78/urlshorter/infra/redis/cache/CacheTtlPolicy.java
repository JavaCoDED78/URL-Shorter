package com.javaded78.urlshorter.infra.redis.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TTL for cached links (ADR-006), with staggered expiry to avoid a database stampede.
 *
 * <p>The base TTL is {@code min(configuredTtl, remainingLinkLifetime)}: never-expiring links get
 * the configured TTL, links with less time left get that remainder, and already-expired links are
 * not cached.
 *
 * <p>A mass of links created together (an import, a campaign) would otherwise share one base TTL
 * and expire in the same second, stampeding PostgreSQL. The singleflight coalescer in {@link
 * RedisLinkCache} smooths that stampede but does not remove it — a burst of expiries still lands
 * within a narrow window. To spread the expiries out instead, a symmetric {@code ±jitterFraction}
 * (default ±15%) is applied to the base TTL, so a link's entry lives for {@code base * (1 + u)}
 * where {@code u ~ Uniform(-jitterFraction, jitterFraction)}.
 */
public final class CacheTtlPolicy {

  /** Upper bound on the jitter fraction: ±50% keeps a TTL inside a sane range, never near zero. */
  private static final double MAX_JITTER = 0.5d;

  private final double jitterFraction;

  /**
   * @param jitterFraction symmetric jitter about the base TTL, in {@code [0, 0.5]}; {@code 0}
   *     disables jitter. Out-of-range values are clamped to this range (the property-level
   *     {@code @PositiveOrZero @DecimalMax("0.5")} validation is the primary guard).
   */
  public CacheTtlPolicy(double jitterFraction) {
    this.jitterFraction = Math.max(0d, Math.min(jitterFraction, MAX_JITTER));
  }

  /**
   * @param expiresAt the link's business expiry, or {@code null} for a never-expiring link
   * @param now the current instant
   * @param configuredTtl the configured cache TTL (never-expiring links use this)
   * @return the jittered TTL to use, or empty when the link is already expired and must not be
   *     written to the cache
   */
  public Optional<Duration> ttlFor(Instant expiresAt, Instant now, Duration configuredTtl) {
    Optional<Duration> base;
    if (expiresAt == null) {
      base = Optional.of(configuredTtl);
    } else if (!expiresAt.isAfter(now)) {
      return Optional.empty();
    } else {
      Duration remaining = Duration.between(now, expiresAt);
      base = Optional.of(remaining.compareTo(configuredTtl) < 0 ? remaining : configuredTtl);
    }
    return base.map(this::applyJitter);
  }

  private Duration applyJitter(Duration ttl) {
    if (jitterFraction <= 0d) {
      return ttl;
    }
    double delta = ThreadLocalRandom.current().nextDouble(-jitterFraction, jitterFraction);
    long jitteredNanos = Math.max(1L, Math.round(ttl.toNanos() * (1d + delta)));
    return Duration.ofNanos(jitteredNanos);
  }
}
