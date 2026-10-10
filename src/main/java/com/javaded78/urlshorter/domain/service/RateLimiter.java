package com.javaded78.urlshorter.domain.service;

/**
 * Rate-limiting port (Task 20.4). The shipped implementation is a no-op so behavior is unchanged;
 * the roadmap replaces it with a Redis-backed (Bucket4j) implementation without touching call
 * sites.
 *
 * <p>Contract for future implementations:
 *
 * <ul>
 *   <li>client-scoped — the {@link RateLimitScope} carries both the limiter <em>kind</em> (e.g.
 *       {@code "create"}) and the per-client key the limiter honours; a single global key that
 *       scopes every caller behind one counter is a bug (an abuser would then block everyone).
 *       The per-client key is resolved per request by a {@link CreateRateLimitSource} and lives
 *       behind the port, never in domain call sites.
 *   <li>probing must not throw and must not consume budget: a denied request can be retried later,
 *       a caller asking twice must get a consistent answer for the same window;
 *   <li>implementation detail (token bucket, fixed window, ...) stays behind this interface —
 *       domain code must not depend on Bucket4j or Redis types.
 * </ul>
 */
public interface RateLimiter {

  /**
   * @param scope the limiter kind and the per-client key to scope by
   * @return {@code true} if a unit of budget for {@code scope} is available (and is atomically
   *     consumed), {@code false} otherwise
   */
  boolean tryAcquire(RateLimitScope scope);
}
