package com.javaded78.urlshorter.infra.redis.counter;

import com.javaded78.urlshorter.domain.counter.ClickCounter;
import com.javaded78.urlshorter.infra.redis.script.CounterDrainScript;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link ClickCounter}: {@code INCR clicks:{lowercaseCode}}.
 *
 * <p>Drain uses SCAN (not blocking KEYS) to find counters and executes the {@link
 * CounterDrainScript} per key — read-and-reset happens atomically inside one Lua run.
 *
 * <p>Increment failure policy (Task 17.2): redirect availability wins. If Redis is unavailable the
 * click is dropped and the caller reports the loss via {@code MetricsRecorder#clickIncrementFailed}
 * — click counting is best-effort analytics, the redirect itself is the product.
 */
@Component
public class RedisClickCounter implements ClickCounter {

  static final String KEY_PREFIX = "clicks:";

  private static final Logger log = LoggerFactory.getLogger(RedisClickCounter.class);

  private final StringRedisTemplate redis;
  private final RedisScript<Long> drainScript;

  public RedisClickCounter(StringRedisTemplate redis, CounterDrainScript drainScript) {
    this.redis = redis;
    this.drainScript = drainScript.get();
  }

  @Override
  public boolean increment(String code) {
    try {
      redis.opsForValue().increment(key(code));
      return true;
    } catch (DataAccessException ex) {
      // Task 17.2: the redirect must survive a dead Redis; the click is
      // dropped (the caller reports the failure via
      // MetricsRecorder#clickIncrementFailed) so redirect availability wins.
      log.warn("click counter unavailable for {} — click dropped: {}", key(code), ex);
      return false;
    }
  }

  @Override
  public long get(String code) {
    try {
      String value = redis.opsForValue().get(key(code));
      // A corrupted/foreign value would surface as an uncontrolled NumberFormatException; treat it
      // as "no counter" (zero) rather than blowing up the read path.
      return value == null ? 0L : safeLong(value);
    } catch (DataAccessException ex) {
      // Task 17.1: a dead Redis makes the pending count invisible; return 0 and let the
      // persisted count stand as the source of truth.
      log.warn("click counter unavailable on read for {}: {}", key(code), ex);
      return 0L;
    }
  }

  private static long safeLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      log.warn("unexpected non-numeric Redis counter value for '{}': '{}'", value, value);
      return 0L;
    }
  }

  @Override
  public Map<String, Long> drainAll() {
    Map<String, Long> drained = new HashMap<>();
    ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build();
    try (Cursor<String> cursor = redis.scan(options)) {
      while (cursor.hasNext()) {
        String redisKey = cursor.next();
        try {
          Long value = redis.execute(drainScript, List.of(redisKey));
          if (value != null && value > 0) {
            drained.put(codeFromKey(redisKey), value);
          }
        } catch (DataAccessException ex) {
          // Task 17.3: a per-key Redis failure mid-scan must not drop counters that were
          // already drained above (their keys are already gone from Redis). The caller's
          // flush recovery restores whatever we did drain; this just keeps the scan going.
          log.warn("counter drain failed for {} — keeping it in Redis: {}", redisKey, ex);
        }
      }
    }
    return drained;
  }

  @Override
  public void restore(String code, long value) {
    try {
      redis.opsForValue().increment(key(code), value);
    } catch (DataAccessException ex) {
      // restore runs after a failed flush write — keep Redis alive so the next cycle
      // can re-drain; the restored value simply waits for the next successful round.
      log.warn("redis unavailable on counter restore for {}: {}", key(code), ex);
    }
  }

  @Override
  public void delete(String code) {
    try {
      redis.delete(key(code));
    } catch (DataAccessException ex) {
      // delete policy (Task 10.3): the DB row is gone, but a failed delete leaves the
      // stale counter behind until it is drained into a missing row. Fail quietly — the
      // counter is best-effort and will be reaped on the next flush.
      log.warn("redis unavailable on counter delete for {}: {}", key(code), ex);
    }
  }

  static String key(String code) {
    return KEY_PREFIX + code.toLowerCase(Locale.ROOT);
  }

  private static String codeFromKey(String redisKey) {
    return redisKey.substring(KEY_PREFIX.length());
  }
}
