package com.javaded78.urlshorter.infra.redis.cache;

import java.time.Instant;

/**
 * Redis value stored under {@code link:{code}} (Task 6.1). Only the fields
 * the redirect path needs — {@code code}, {@code originalUrl},
 * {@code expiresAt}; nothing else is cached.
 *
 * <p>The entry carries {@code expiresAt} on purpose: expiration is business
 * logic and must be re-checked on every cache hit, not delegated to the
 * Redis TTL.
 */
public record CachedLink(String code, String originalUrl, Instant expiresAt) {

    public CachedLink {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be null or blank");
        }
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new IllegalArgumentException("originalUrl must not be null or blank");
        }
    }

    /**
     * Same policy as the domain: {@code expiresAt <= now} is expired,
     * {@code null} never expires. All timestamps are UTC.
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
