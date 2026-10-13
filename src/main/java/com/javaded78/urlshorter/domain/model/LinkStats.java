package com.javaded78.urlshorter.domain.model;

/**
 * Click statistics for a code (ADR-003): the logically current count — persisted PostgreSQL value
 * plus clicks still pending in Redis.
 */
public record LinkStats(String code, long clickCount) {}
