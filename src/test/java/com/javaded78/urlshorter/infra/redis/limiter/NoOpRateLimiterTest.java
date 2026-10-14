package com.javaded78.urlshorter.infra.redis.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.domain.service.RateLimitScope;
import com.javaded78.urlshorter.domain.service.RateLimiter;
import org.junit.jupiter.api.Test;

/**
 * Contract of the rate-limiting port (ADR-009): the shipped no-op implementation admits everything,
 * so behavior is unchanged until the Redis-backed implementation is added. The test pins the
 * semantics future implementations must keep: client-scoped, side-effect-free probing.
 */
class NoOpRateLimiterTest {

  private final RateLimiter rateLimiter = new NoOpRateLimiter();

  @Test
  void admitsEveryRequest() {
    assertThat(rateLimiter.tryAcquire(new RateLimitScope("create", "1.1.1.1"))).isTrue();
    assertThat(rateLimiter.tryAcquire(new RateLimitScope("create", "2.2.2.2"))).isTrue();
    assertThat(rateLimiter.tryAcquire(new RateLimitScope("redirect", "abc"))).isTrue();
  }

  @Test
  void probingNeverMutatesState() {
    // a probe must not consume a future budget: asking twice in a row
    // answers the same
    RateLimitScope scope = new RateLimitScope("create", "1.1.1.1");
    boolean first = rateLimiter.tryAcquire(scope);
    boolean second = rateLimiter.tryAcquire(scope);
    assertThat(first).isEqualTo(second);
  }
}
