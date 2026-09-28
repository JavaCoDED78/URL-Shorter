package com.javaded78.urlshorter.infra.redis.cache;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Redis value stored under {@code link:{code}} (Task 6.1). Only the fields
 * the redirect path needs — {@code code}, {@code originalUrl},
 * {@code expiresAt}; nothing else is cached.
 *
 * <p>The entry carries {@code expiresAt} on purpose: expiration is business
 * logic and must be re-checked on every cache hit, not delegated to the
 * Redis TTL.
 *
 * <p>Wire-format contract (Task 19.4): plain JSON via a dedicated mapper
 * (ISO-8601 timestamps, {@code expiresAt: null} for never-expiring links) —
 * never JVM serialization, so entries survive across application versions
 * and restarts. Reading is forward-compatible: fields unknown to this
 * version are ignored, so an entry written by a newer release keeps serving
 * until its TTL expires; a structurally unreadable entry is evicted and the
 * redirect falls back to PostgreSQL (Task 6.5).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
