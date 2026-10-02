package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Contract of the rate-limiting port (Task 20.4): the shipped no-op implementation admits
 * everything, so behavior is unchanged until the Redis-backed implementation (Bucket4j, see
 * roadmap) is added. The test pins the semantics future implementations must keep: key-scoped,
 * side-effect-free probing.
 */
class NoOpRateLimiterTest {

  private final RateLimiter rateLimiter = new NoOpRateLimiter();

  @Test
  void admitsEveryRequest() {
    assertThat(rateLimiter.tryAcquire("create")).isTrue();
    assertThat(rateLimiter.tryAcquire("create")).isTrue();
    assertThat(rateLimiter.tryAcquire("redirect:s20x")).isTrue();
  }

  @Test
  void probingNeverMutatesState() {
    // a probe must not consume a future budget: asking twice in a row
    // answers the same
    boolean first = rateLimiter.tryAcquire("same-key");
    boolean second = rateLimiter.tryAcquire("same-key");
    assertThat(first).isEqualTo(second);
  }
}
