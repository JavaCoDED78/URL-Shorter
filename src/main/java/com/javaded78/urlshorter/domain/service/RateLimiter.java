package com.javaded78.urlshorter.domain.service;

/**
 * Rate-limiting port (Task 20.4). The shipped implementation is a no-op so
 * behavior is unchanged; the roadmap replaces it with a Redis-backed
 * (Bucket4j) implementation without touching call sites.
 *
 * <p>Contract for future implementations:
 * <ul>
 *   <li>key-scoped — e.g. {@code "create"} for the create endpoint or
 *   {@code "redirect:" + code} for redirects;</li>
 *   <li>probing must not throw and must not consume budget: a denied
 *   request can be retried later, a caller asking twice must get a
 *   consistent answer for the same window;</li>
 *   <li>implementation detail (token bucket, fixed window, ...) stays
 *   behind this interface — domain code must not depend on Bucket4j or
 *   Redis types.</li>
 * </ul>
 */
public interface RateLimiter {

    /**
     * @return {@code true} if a unit of budget for {@code key} is available
     *         (and is atomically consumed), {@code false} otherwise
     */
    boolean tryAcquire(String key);
}
