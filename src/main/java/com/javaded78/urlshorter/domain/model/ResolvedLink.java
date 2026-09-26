package com.javaded78.urlshorter.domain.model;

/**
 * Result of resolving a code for redirection (Sprint 6): exactly what the
 * redirect path needs — the canonical code and the destination URL. Unlike
 * {@link Link} it carries no stats or timestamps, so it can be served from a
 * cache entry that stores only redirect-relevant fields.
 */
public record ResolvedLink(String code, String originalUrl) {

    public ResolvedLink {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be null or blank");
        }
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new IllegalArgumentException("originalUrl must not be null or blank");
        }
    }
}
