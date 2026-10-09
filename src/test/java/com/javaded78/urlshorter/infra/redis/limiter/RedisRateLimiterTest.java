package com.javaded78.urlshorter.infra.redis.limiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Key-scoped fixed-window rate limiter (Task 20.4): a single Redis counter ({@code
 * ratelimit:{key}}) is {@code INCR}ed on every probe. {@code INCR} and the window {@code EXPIRE}
 * run inside one atomic Lua script (see {@link RateLimitScript}), so the count and its TTL are
 * never two separate round-trips that could drift apart. Admission is {@code count <= maxRequests}.
 */
class RedisRateLimiterTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private final MetricsRecorder metrics = new MetricsRecorder(registry);

  private final RateLimitScript script = new RateLimitScript();

  private final RedisRateLimiter limiter =
      new RedisRateLimiter(redis, Duration.ofSeconds(10), 5, metrics, script);

  RedisRateLimiterTest() {
    // INCR inside the Lua script: first four probes admit, fifth exceeds the ceiling.
    when(redis.execute(any(), anyList(), any())).thenReturn(1L, 2L, 3L, 4L, 6L);
  }

  @Test
  void admitsUpToLimitThenDenies() {
    assertThat(limiter.tryAcquire("create")).isTrue();
    assertThat(limiter.tryAcquire("create")).isTrue();
    assertThat(limiter.tryAcquire("create")).isTrue();
    assertThat(limiter.tryAcquire("create")).isTrue();
    assertThat(limiter.tryAcquire("create")).isFalse();
  }

  @Test
  void oneLuaRoundTripPerProbeArmsWindow() {
    when(redis.execute(any(), anyList(), any())).thenReturn(1L, 2L);

    limiter.tryAcquire("create");
    limiter.tryAcquire("create");

    // One Lua round-trip per probe — the window TTL is armed inside the script, so there is a
    // single atomic call per probe (no separate INCR then EXPIRE round-trip).
    verify(redis, times(2)).execute(any(), anyList(), any());
  }

  @Test
  void keysAreNamespacedSoTheyDoNotCollide() {
    assertThat(limiter.tryAcquire("create")).isTrue();
    assertThat(limiter.tryAcquire("redirect:abc")).isTrue();
    verify(redis, times(2)).execute(any(), anyList(), any());
  }

  @Test
  void failureOnRedisAllowsToPreserveAvailability() {
    when(redis.execute(any(), anyList(), any()))
        .thenAnswer(
            invocation -> {
              throw new InvalidDataAccessApiUsageException("redis down");
            });

    assertThat(limiter.tryAcquire("create")).isTrue();
    // fail-open: the request is allowed through, so nothing is counted as a denial.
    assertThat(registry.counter("urlshorter.rateLimiterDenied").count()).isEqualTo(0);
  }
}
