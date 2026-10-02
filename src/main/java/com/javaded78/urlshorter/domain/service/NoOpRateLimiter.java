package com.javaded78.urlshorter.domain.service;

import org.springframework.stereotype.Component;

/**
 * Default {@link RateLimiter} (Task 20.4): admits everything. Exists so the port is wired and
 * testable today; the Redis-backed Bucket4j implementation on the roadmap replaces this bean
 * without changing call sites or application behavior when disabled.
 */
@Component
public class NoOpRateLimiter implements RateLimiter {

  @Override
  public boolean tryAcquire(String key) {
    return true;
  }
}
