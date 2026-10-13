package com.javaded78.urlshorter.domain.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Domain representation of a short link — business data only, no persistence or cache annotations.
 *
 * @param code short code; the unique business identifier
 * @param originalUrl destination URL
 * @param expiresAt expiration instant in UTC, or {@code null} when the link never expires
 * @param clickCount number of recorded clicks; never negative
 * @param createdAt creation instant in UTC
 */
public record Link(
    String code,
    String originalUrl,
    @Nullable Instant expiresAt,
    long clickCount,
    Instant createdAt) {

  public Link {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be null or blank");
    }
    if (originalUrl == null || originalUrl.isBlank()) {
      throw new IllegalArgumentException("originalUrl must not be null or blank");
    }
    if (clickCount < 0) {
      throw new IllegalArgumentException("clickCount must not be negative: " + clickCount);
    }
    if (createdAt == null) {
      throw new IllegalArgumentException("createdAt must not be null");
    }
  }

  /**
   * Creates a link with zero click count — the write path supplies the persistence instant
   * (normally {@code clock.instant()}), keeping link creation deterministic under an injected
   * {@link Clock} in tests.
   */
  public static Link created(
      String code, String originalUrl, @Nullable Instant expiresAt, Instant createdAt) {
    return new Link(code, originalUrl, expiresAt, 0, createdAt);
  }
}
