package com.javaded78.urlshorter.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.javaded78.urlshorter.domain.model.LinkStats;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Statistics response (Task 9.3): persisted DB count plus pending Redis clicks — the logically
 * current click count.
 */
@Schema(
    description =
        "Click statistics: persisted PostgreSQL count plus clicks still "
            + "pending in Redis, so the value is current without waiting for the flush")
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LinkStatsResponse(
    @Schema(description = "Short code of the link", example = "abc1234") String code,
    @Schema(
            description = "Current click count (persisted + pending)",
            example = "42",
            type = "integer",
            format = "int64")
        long clickCount) {

  public static LinkStatsResponse from(LinkStats stats) {
    return new LinkStatsResponse(stats.code(), stats.clickCount());
  }
}
