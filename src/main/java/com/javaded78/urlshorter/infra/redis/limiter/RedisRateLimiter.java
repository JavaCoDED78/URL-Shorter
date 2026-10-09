package com.javaded78.urlshorter.infra.redis.limiter;

import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import com.javaded78.urlshorter.domain.service.RateLimiter;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed key-scoped fixed-window rate limiter (Task 20.4).
 *
 * <p>One Redis key per {@code key} ({@code ratelimit:{key}}) holds a plain integer bumped by {@code
 * INCR} on every probe. Redis self-manages the window: the first probe of a window (the {@code
 * INCR} result is {@code 1}) arms a {@link Duration} TTL on that key via {@code EXPIRE}; once the
 * window elapses Redis deletes it and the next probe naturally starts the window anew. This keeps
 * expiry in Redis (no clock arithmetic, no stale-window bookkeeping) and uses a single key — there
 * is nothing to drain or re-arm explicitly.
 *
 * <p>{@code INCR} and {@code EXPIRE} run inside a single atomic Lua script (see {@link
 * RateLimitScript}) — there is no interleaving window between the increment and the TTL arm, so a
 * probe can never leave a key behind without its TTL.
 *
 * <p>Admission is {@code count <= maxRequests}; the {@code maxRequests}-th probe of the window is
 * the last one admitted, the next is denied.
 *
 * <p>Failure policy: a Redis outage is <em>fail-open</em>. A denied probe on a healthy limiter
 * returns {@code false} and is counted via {@code rateLimiterDenied}; a Redis failure returns
 * {@code true} so a create request is never lost to a transient infra outage — the same
 * availability-wins stance as Tasks 17.1/17.2. The limiter is a best-effort defense, not a
 * correctness guarantee.
 *
 * <p>The {@code ratelimit:} namespace is namespaced so it never collides with the {@code link:} or
 * {@code clicks:} keys used elsewhere.
 *
 * <p>Bean registration is conditional and property-driven (see {@code RateLimitConfig}).
 */
public class RedisRateLimiter implements RateLimiter {

  static final String KEY_PREFIX = "ratelimit:";

  private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

  private final StringRedisTemplate redis;
  private final Duration window;
  private final int maxRequests;
  private final MetricsRecorder metrics;
  private final RateLimitScript script;

  public RedisRateLimiter(
      StringRedisTemplate redis,
      Duration window,
      int maxRequests,
      MetricsRecorder metrics,
      RateLimitScript script) {
    this.redis = redis;
    this.window = window;
    this.maxRequests = maxRequests;
    this.metrics = metrics;
    this.script = script;
  }

  @Override
  public boolean tryAcquire(String key) {
    try {
      final String counterKey = KEY_PREFIX + key;
      // INCR atomically creates the key (returning 1) on the first probe of a window, or bumps
      // it on subsequent probes. INCR and EXPIRE run inside one Lua script, so Redis owns the
      // window: the freshly-created key gets a TTL and Redis self-deletes it when the window
      // elapses, so the next probe INCRs from 1 again.
      final Long count =
          redis.execute(script.get(), List.of(counterKey), RateLimitScript.windowInSeconds(window));
      boolean denied = count == null || count > maxRequests;
      if (denied) {
        metrics.rateLimiterDenied();
      }
      return !denied;
    } catch (DataAccessException ex) {
      // Redis down (or a transient command failure): fail open. The create path must survive a
      // dead limiter; the request is allowed through and simply not counted as a denial.
      log.warn("rate limiter unavailable — admitting key {} (Task 20.4): {}", key, ex);
      return true;
    }
  }
}
