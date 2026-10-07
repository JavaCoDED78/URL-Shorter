package com.javaded78.urlshorter.domain.service;

/**
 * Default {@link RateLimiter} (Task 20.4): admits everything. Bound by {@link RateLimitConfig} when
 * {@code shortener.rate-limiter.enabled=false}; the Redis-backed {@code RedisRateLimiter} replaces
 * it when the limiter is enabled, without changing call sites.
 */
public class NoOpRateLimiter implements RateLimiter {

  @Override
  public boolean tryAcquire(String key) {
    return true;
  }
}
