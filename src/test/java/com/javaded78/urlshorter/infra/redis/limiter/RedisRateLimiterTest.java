package com.javaded78.urlshorter.infra.redis.limiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import com.javaded78.urlshorter.domain.service.RateLimitScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Client-scoped fixed-window rate limiter (Task 20.4): a single Redis counter ({@code
 * ratelimit:{key}}) is {@code INCR}ed on every probe. {@code INCR} and the window {@code EXPIRE}
 * run inside one atomic Lua script (see {@link RateLimitScript}), so the count and its TTL are
 * never two separate round-trips that could drift apart. Admission is {@code count <= maxRequests}.
 */
class RedisRateLimiterTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private final MetricsRecorder metrics = new MetricsRecorder(registry);

  private final RateLimitScript script = new RateLimitScript();

  private final RateLimitScope scope = new RateLimitScope("create", "203.0.113.9");

  private final RedisRateLimiter limiter =
      new RedisRateLimiter(redis, Duration.ofSeconds(10), 5, true, metrics, script);

  RedisRateLimiterTest() {
    // INCR inside the Lua script: first four probes admit, fifth exceeds the ceiling.
    when(redis.execute(any(), anyList(), any())).thenReturn(1L, 2L, 3L, 4L, 6L);
  }

  @Test
  void admitsUpToLimitThenDenies() {
    assertThat(limiter.tryAcquire(scope)).isTrue();
    assertThat(limiter.tryAcquire(scope)).isTrue();
    assertThat(limiter.tryAcquire(scope)).isTrue();
    assertThat(limiter.tryAcquire(scope)).isTrue();
    assertThat(limiter.tryAcquire(scope)).isFalse();
  }

  @Test
  void oneLuaRoundTripPerProbeArmsWindow() {
    when(redis.execute(any(), anyList(), any())).thenReturn(1L, 2L);

    limiter.tryAcquire(scope);
    limiter.tryAcquire(scope);

    // One Lua round-trip per probe — the window TTL is armed inside the script, so there is a
    // single atomic call per probe (no separate INCR then EXPIRE round-trip).
    verify(redis, times(2)).execute(any(), anyList(), any());
  }

  @Test
  void countersArePerClientScope() {
    assertThat(limiter.tryAcquire(new RateLimitScope("create", "1.1.1.1"))).isTrue();
    assertThat(limiter.tryAcquire(new RateLimitScope("create", "2.2.2.2"))).isTrue();
    verify(redis, times(2)).execute(any(), anyList(), any());
  }

  @Test
  void failureOnRedisAllowsToPreserveAvailability() {
    when(redis.execute(any(), anyList(), any()))
        .thenAnswer(
            invocation -> {
              throw new InvalidDataAccessApiUsageException("redis down");
            });

    assertThat(limiter.tryAcquire(scope)).isTrue();
    // fail-open: the request is allowed through, so nothing is counted as a denial.
    assertThat(registry.counter("urlshorter.rateLimiterDenied").count()).isEqualTo(0);
  }

  @Test
  void failureOnRedisFailsClosedWhenConfigured() {
    RedisRateLimiter closed =
        new RedisRateLimiter(redis, Duration.ofSeconds(10), 5, false, metrics, script);
    when(redis.execute(any(), anyList(), any()))
        .thenAnswer(
            invocation -> {
              throw new InvalidDataAccessApiUsageException("redis down");
            });

    assertThat(closed.tryAcquire(scope)).isFalse();
  }

  @Test
  void trustedProxyExposureIsScopedNotGlobal() {
    // A single abuser exhausting their own budget cannot drain another client's budget — each
    // RateLimitScope has its own counter behind its own key. The mock returns the same counter
    // for both keys, so we sequence: A admits, A admits (now at the limit), A denied, then B
    // (a different client) still admitted.
    RedisRateLimiter limiterTwo =
        new RedisRateLimiter(redis, Duration.ofSeconds(10), 2, true, metrics, script);
    when(redis.execute(any(), anyList(), any())).thenReturn(1L, 2L, 3L, 1L);

    assertThat(limiterTwo.tryAcquire(new RateLimitScope("create", "10.0.0.1"))).isTrue();
    assertThat(limiterTwo.tryAcquire(new RateLimitScope("create", "10.0.0.1"))).isTrue();
    assertThat(limiterTwo.tryAcquire(new RateLimitScope("create", "10.0.0.1"))).isFalse();
    assertThat(limiterTwo.tryAcquire(new RateLimitScope("create", "10.0.0.2"))).isTrue();
  }
}
