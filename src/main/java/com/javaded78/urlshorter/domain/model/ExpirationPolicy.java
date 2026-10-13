package com.javaded78.urlshorter.domain.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Expiration policy (ADR-006): {@code null} never expires; {@code expiresAt > now} is active;
 * {@code expiresAt <= now} is expired.
 *
 * <p>Centralized so the domain model and the cache value ({@code domain.cache.CachedLink}) never
 * diverge on this business rule — every caller re-checks {@code expiresAt} on every read, because
 * expiration is business logic, not a Redis TTL side effect.
 */
public final class ExpirationPolicy {

  private ExpirationPolicy() {}

  /** {@code expiresAt == null} never expires; {@code expiresAt <= now} is expired. */
  public static boolean isExpired(@Nullable Instant expiresAt, Instant now) {
    return expiresAt != null && !expiresAt.isAfter(now);
  }
}
