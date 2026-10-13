/**
 * Redis-backed rate-limiting implementations (ADR-009).
 *
 * <p>Key-scoped, fixed-window limiter that protects the create endpoint from abuse. The limiter is
 * a best-effort defense — it is wired through the {@code RateLimiter} port in {@code
 * domain.service} and degrades fail-open on a Redis outage.
 */
@org.jspecify.annotations.NullMarked
package com.javaded78.urlshorter.infra.redis.limiter;
