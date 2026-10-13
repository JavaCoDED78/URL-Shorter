package com.javaded78.urlshorter.infra.redis.limiter;

import java.time.Duration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Atomic key-scoped fixed-window rate-limit probe (ADR-009).
 *
 * <p>Runs {@code INCR} and, only when the freshly-created key (the {@code INCR} result is {@code
 * 1}), {@code EXPIRE} on that key — all inside a single Lua execution. Redis runs one script
 * atomically, so there is no interleaving window between the increment and the TTL arm: a probe
 * cannot leave a key behind without its TTL (which the two-command {@code INCR}+{@code EXPIRE}
 * pattern would risk if the process died between the two round-trips), and the count is never
 * bumped twice for one probe.
 *
 * <p>{@code KEYS[1]} is the limiter key ({@code ratelimit:{key}}); {@code ARGV[1]} is the window in
 * seconds. Returns the post-increment count as a {@link Long}.
 */
@Component
public class RateLimitScript {

  private static final String LUA =
      """
          local count = redis.call('INCR', KEYS[1])
          if count == 1 then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
          end
          return count
          """;

  private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA, Long.class);

  public DefaultRedisScript<Long> get() {
    return script;
  }

  /** Redis-script argument: the window expressed in whole seconds. */
  static String windowInSeconds(Duration window) {
    return String.valueOf(window.getSeconds());
  }
}
