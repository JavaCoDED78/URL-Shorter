package com.javaded78.urlshorter.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.javaded78.urlshorter.domain.model.LinkStats;

/**
 * Statistics response (Task 9.3): persisted DB count plus pending Redis
 * clicks — the logically current click count.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LinkStatsResponse(String code, long clickCount) {

    public static LinkStatsResponse from(LinkStats stats) {
        return new LinkStatsResponse(stats.code(), stats.clickCount());
    }
}
