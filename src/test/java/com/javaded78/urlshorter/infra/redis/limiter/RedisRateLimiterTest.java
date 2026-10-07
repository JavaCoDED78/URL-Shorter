package com.javaded78.urlshorter.infra.redis.limiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.data.redis.core.ValueOperations;

/**
 * Key-scoped fixed-window rate limiter (Task 20.4): a single Redis counter ({@code
 * ratelimit:{key}}) is {@code INCR}ed on every probe; the first probe of a window arms a {@code
 * Duration} TTL via {@code EXPIRE}, after which Redis self-deletes the key and the next probe
 * restarts the window. Admission is {@code count <= maxRequests}.
 */
class RedisRateLimiterTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private final MetricsRecorder metrics = new MetricsRecorder(registry);

  private final RedisRateLimiter limiter =
      new RedisRateLimiter(redis, Duration.ofSeconds(10), 5, metrics);

  RedisRateLimiterTest() {
    when(redis.opsForValue()).thenReturn(ops);
  }

  @Test
  void admitsUpToLimitThenDenies() {
    // First four probes: INCR returns 1..4, all admitted; the fifth probe exceeds the limit.
    when(ops.increment("ratelimit:create")).thenReturn(1L, 2L, 3L, 4L, 6L);

    assertThat(limiter.tryAcquire("create")).isTrue();
    assertThat(limiter.tryAcquire("create")).isTrue();
    assertThat(limiter.tryAcquire("create")).isTrue();
    assertThat(limiter.tryAcquire("create")).isTrue();
    assertThat(limiter.tryAcquire("create")).isFalse();
  }

  @Test
  void firstProbeOfWindowArmsTtl() {
    when(ops.increment("ratelimit:create")).thenReturn(1L, 2L);

    assertThat(limiter.tryAcquire("create")).isTrue();
    assertThat(limiter.tryAcquire("create")).isTrue();

    // The TTL is armed exactly once — on the first probe of the window. Redis owns the window and
    // self-deletes the key when it elapses, so subsequent probes never re-arm it.
    verify(redis, times(1)).expire(anyString(), any(Duration.class));
  }

  @Test
  void keysAreNamespacedSoTheyDoNotCollide() {
    when(ops.increment(anyString())).thenReturn(1L);

    assertThat(limiter.tryAcquire("create")).isTrue();
    assertThat(limiter.tryAcquire("redirect:abc")).isTrue();
    verify(redis, times(2)).expire(anyString(), any(Duration.class));
  }

  @Test
  void failureOnRedisAllowsToPreserveAvailability() {
    when(ops.increment("ratelimit:create"))
        .thenAnswer(
            invocation -> {
              throw new InvalidDataAccessApiUsageException("redis down");
            });

    assertThat(limiter.tryAcquire("create")).isTrue();
    // fail-open: the request is allowed through, so nothing is counted as a denial.
    assertThat(registry.counter("urlshorter.rateLimiterDenied").count()).isEqualTo(0);
  }
}
