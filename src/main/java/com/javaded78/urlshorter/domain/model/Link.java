package com.javaded78.urlshorter.domain.model;

import java.time.Instant;

/**
 * Domain representation of a short link — business data only, no persistence
 * or cache annotations (Task 3.1).
 *
 * @param code         short code; the unique business identifier
 * @param originalUrl  destination URL
 * @param expiresAt    expiration instant in UTC, or {@code null} when the link
 *                     never expires
 * @param clickCount   number of recorded clicks; never negative
 * @param createdAt    creation instant in UTC
 */
public record Link(String code, String originalUrl, Instant expiresAt, long clickCount, Instant createdAt) {

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
     * Expiration policy (Task 5.2): {@code expiresAt == null} never expires;
     * {@code expiresAt > now} is active; {@code expiresAt <= now} is expired.
     * All timestamps are UTC {@link Instant}s.
     *
     * @param now the reference instant (pass {@code Instant.now()} in prod,
     *            a fixed instant in tests)
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
