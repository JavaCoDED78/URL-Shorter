package com.javaded78.urlshorter.domain.service;

/**
 * The identity the create-rate limiter is scoped by (Task 20.4 hardening).
 *
 * <p>A scope carries a limiter <em>kind</em> ({@code create}) and the per-client key the limiter
 * honours. The key is resolved per request by a {@link CreateRateLimitSource} — for the create
 * endpoint that is the client's identity derived from the (optionally trusted) proxy chain, not a
 * single global value.
 *
 * <p>The value type keeps the {@link RateLimiter} signature free of any HTTP-request type and lets
 * the domain assert that every probe is explicitly client-scoped.
 */
public record RateLimitScope(String kind, String clientKey) {}
