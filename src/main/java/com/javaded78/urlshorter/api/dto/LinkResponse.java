package com.javaded78.urlshorter.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.javaded78.urlshorter.domain.model.Link;

/**
 * API representation of a link, returned by create and detail endpoints.
 * Null fields are serialized explicitly so clients can rely on a stable
 * contract (e.g. {@code expiresAt} is always present).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LinkResponse(
        String code,
        String originalUrl,
        Instant expiresAt,
        long clickCount,
        Instant createdAt) {

    public static LinkResponse from(Link link) {
        return new LinkResponse(
                link.code(),
                link.originalUrl(),
                link.expiresAt(),
                link.clickCount(),
                link.createdAt());
    }
}
