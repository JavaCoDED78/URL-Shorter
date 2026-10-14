package com.javaded78.urlshorter.infra.redis.limiter;

import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import com.javaded78.urlshorter.domain.service.RateLimitScope;
import com.javaded78.urlshorter.domain.service.RateLimiter;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed client-scoped fixed-window rate limiter (ADR-009).
 *
 * <p>One Redis key per client scope ({@code ratelimit:{key}}) holds a plain integer bumped by
 * {@code INCR} on every probe. Redis self-manages the window: the first probe of a window (the
 * {@code INCR} result is {@code 1}) arms a {@link Duration} TTL on that key via {@code EXPIRE};
 * once the window elapses Redis deletes it and the next probe naturally starts the window anew.
 * This keeps expiry in Redis (no clock arithmetic, no stale-window bookkeeping) and uses a single
 * key — there is nothing to drain or re-arm explicitly.
 *
 * <p>{@code INCR} and {@code EXPIRE} run inside a single atomic Lua script (see {@link
 * RateLimitScript}) — there is no interleaving window between the increment and the TTL arm, so a
 * probe can never leave a key behind without its TTL.
 *
 * <p>Admission is {@code count <= maxRequests}; the {@code maxRequests}-th probe of the window is
 * the last one admitted, the next is denied.
 *
 * <p>Per-client scoping (ADR-009): the probe key is the caller's IP (resolved by the {@code
 * CreateRateLimitSource} that builds the {@link RateLimitScope} in {@code WriteLinkService}), so
 * each client has its own counter. A single global key would let one abuser block every client —
 * the limiter must never be keyed on a constant.
 *
 * <p>Failure policy: a Redis outage is {@code fail-open} or {@code fail-closed} as configured by
 * {@code shortener.rate-limiter.fail-open} (default {@code true}). A denied probe on a healthy
 * limiter returns {@code false} and is counted via {@code rateLimiterDenied}; on a Redis failure
 * the outcome is decided by the policy so a transient outage neither floods the endpoint
 * (fail-open) nor blocks every client (fail-closed). The limiter is a best-effort defense, not a
 * correctness guarantee.
 *
 * <p>The {@code ratelimit:} namespace is namespaced so it never collides with the {@code link:} or
 * {@code clicks:} keys used elsewhere.
 *
 * <p>Bean registration is conditional and property-driven (see {@code RateLimitConfig}).
 */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

  static final String KEY_PREFIX = "ratelimit:";

  private final StringRedisTemplate redis;
  private final Duration window;
  private final int maxRequests;
  private final boolean failOpen;
  private final MetricsRecorder metrics;
  private final RateLimitScript script;

  public RedisRateLimiter(
      StringRedisTemplate redis,
      Duration window,
      int maxRequests,
      boolean failOpen,
      MetricsRecorder metrics,
      RateLimitScript script) {
    this.redis = redis;
    this.window = window;
    this.maxRequests = maxRequests;
    this.failOpen = failOpen;
    this.metrics = metrics;
    this.script = script;
  }

  @Override
  public boolean tryAcquire(RateLimitScope scope) {
    try {
      final String counterKey = KEY_PREFIX + scope.clientKey();
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
      // Redis down (or a transient command failure): the outcome depends on policy. Fail open —
      // the default — lets creates ride through a transient outage (same availability-wins stance
      // as availability-relevant); fail closed treats a broken limiter as a refusal instead of an
      // open
      // door. Either way the request is not counted as a denial.
      if (failOpen) {
        log.warn(
            "rate limiter unavailable ({}) — admitting scope {}: {}",
            scope.kind(),
            scope.clientKey(),
            ex);
        return true;
      }
      log.warn(
          "rate limiter unavailable ({}) — refusing scope {}: {}",
          scope.kind(),
          scope.clientKey(),
          ex);
      return false;
    }
  }
}
