package com.javaded78.urlshorter.api.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request to create a short link")
public record CreateLinkRequest(

        @Schema(description = "Destination URL the short code redirects to",
                example = "https://example.com/very/long/path?with=query",
                requiredMode = Schema.RequiredMode.REQUIRED,
                maxLength = 2048)
        @NotBlank(message = "originalUrl must not be blank")
        @Size(max = 2048, message = "originalUrl exceeds the maximum length")
        String originalUrl,

        @Schema(description = "Optional custom alias; lowercase letters, digits, '-' and '_'; "
                + "a taken alias answers 409",
                example = "my-link",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                maxLength = 64,
                pattern = "^[a-zA-Z0-9_-]+$")
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "alias may only contain letters, digits, '-' and '_'")
        @Size(max = 64, message = "alias exceeds the maximum length")
        String alias,

        @Schema(description = "Optional expiration instant (ISO-8601, UTC); must be in the "
                + "future; absent means the link never expires",
                example = "2027-01-01T00:00:00Z",
                type = "string",
                format = "date-time",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant expiresAt) {
}
