package com.javaded78.urlshorter.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/links} (Task 4.1).
 *
 * @param originalUrl destination URL; required, validated structurally here
 *                    (non-blank, length) and semantically in the service
 *                    (scheme/host)
 * @param alias       optional custom alias; {@code [a-zA-Z0-9_-]+}, semantic
 *                    checks (reserved words, case policy) happen in the service
 * @param expiresAt   optional expiration instant (ISO-8601); {@code null}
 *                    means the link never expires
 */
public record CreateLinkRequest(

        @NotBlank(message = "originalUrl must not be blank")
        @Size(max = 2048, message = "originalUrl exceeds the maximum length")
        String originalUrl,

        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "alias may only contain letters, digits, '-' and '_'")
        @Size(max = 64, message = "alias exceeds the maximum length")
        String alias,

        Instant expiresAt) {
}
