package com.javaded78.urlshorter.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.javaded78.urlshorter.domain.model.Link;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * API representation of a link, returned by create and detail endpoints. Null fields are serialized
 * explicitly so clients can rely on a stable contract (e.g. {@code expiresAt} is always present).
 */
@Schema(description = "Short link representation returned by create and detail endpoints")
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LinkResponse(
    @Schema(description = "Short code used in redirects; case-insensitive", example = "abc1234")
        String code,
    @Schema(description = "Destination URL", example = "https://example.com/") String originalUrl,
    @Schema(
            description =
                "Expiration instant (ISO-8601, UTC); null means the link " + "never expires",
            example = "2027-01-01T00:00:00Z",
            type = "string",
            format = "date-time",
            nullable = true)
        Instant expiresAt,
    @Schema(
            description =
                "Persisted click count; clicks still pending in Redis are "
                    + "not included — use the stats endpoint for the current value",
            example = "42",
            type = "integer",
            format = "int64")
        long clickCount,
    @Schema(description = "Creation instant (ISO-8601, UTC)", type = "string", format = "date-time")
        Instant createdAt) {

  public static LinkResponse from(Link link) {
    return new LinkResponse(
        link.code(), link.originalUrl(), link.expiresAt(), link.clickCount(), link.createdAt());
  }
}
