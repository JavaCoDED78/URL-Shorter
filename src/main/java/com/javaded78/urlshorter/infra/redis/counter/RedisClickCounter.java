package com.javaded78.urlshorter.infra.redis.counter;

import com.javaded78.urlshorter.domain.counter.ClickCounter;
import com.javaded78.urlshorter.infra.redis.script.CounterDrainScript;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link ClickCounter}: {@code INCR clicks:{lowercaseCode}}.
 *
 * <p>Drain uses SCAN (not blocking KEYS) to find counters, then drains them with a single {@link
 * CounterDrainScript} per batch of keys — read-and-reset happens atomically inside one Lua run, so
 * the drain is O(number of batches) round-trips instead of O(number of counters) and stops scaling
 * with the number of hot links.
 *
 * <p>Increment failure policy (Task 17.2): redirect availability wins. If Redis is unavailable the
 * click is dropped and the caller reports the loss via {@code MetricsRecorder#clickIncrementFailed}
 * — click counting is best-effort analytics, the redirect itself is the product.
 */
@Component
public class RedisClickCounter implements ClickCounter {

  static final String KEY_PREFIX = "clicks:";

  private static final int DEFAULT_BATCH_SIZE = 1000;

  private static final Logger log = LoggerFactory.getLogger(RedisClickCounter.class);

  private final StringRedisTemplate redis;
  private final RedisScript<List<String>> drainScript;
  private final int batchSize;

  @Autowired
  public RedisClickCounter(StringRedisTemplate redis, CounterDrainScript drainScript) {
    this(redis, drainScript, DEFAULT_BATCH_SIZE);
  }

  RedisClickCounter(StringRedisTemplate redis, CounterDrainScript drainScript, int batchSize) {
    this.redis = redis;
    this.drainScript = drainScript.batch();
    this.batchSize = batchSize;
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
    List<String> keys = discoverKeys();
    Map<String, Long> drained = new HashMap<>();
    for (int from = 0; from < keys.size(); from += batchSize) {
      List<String> batch = keys.subList(from, Math.min(from + batchSize, keys.size()));
      try {
        List<String> reply = redis.execute(drainScript, batch);
        accumulate(drained, reply);
      } catch (DataAccessException ex) {
        // Atomic batch: either the whole batch drained or none of it did. A failed batch leaves its
        // keys intact in Redis for the next cycle; already-drained earlier batches are returned to
        // the caller (flushed or restored by CounterFlushService). This preserves the per-key
        // fail-open policy — nothing is silently lost.
        log.warn(
            "counter drain batch {}..{} failed — keys remain in Redis, retry next cycle",
            from,
            Math.min(from + batchSize, keys.size()) - 1,
            ex);
      }
    }
    return drained;
  }

  /** Discovers every {@code clicks:*} counter key with non-blocking SCAN (count 100). */
  private List<String> discoverKeys() {
    ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build();
    List<String> keys = new ArrayList<>();
    try (Cursor<String> cursor = redis.scan(options)) {
      while (cursor.hasNext()) {
        keys.add(cursor.next());
      }
    }
    return keys;
  }

  /**
   * Fills {@code drained} from a batch script reply, which is a flat {@code [key, value, key,
   * value, ...]} array of raw strings. Pairs them up and keeps only counters whose drained value is
   * positive. A trailing odd element (defensive — the script only ever emits pairs) is ignored.
   */
  private static void accumulate(Map<String, Long> drained, List<String> reply) {
    if (reply == null || reply.isEmpty()) {
      return;
    }
    for (int i = 0; i + 1 < reply.size(); i += 2) {
      long count = safeLong(reply.get(i + 1));
      if (count > 0) {
        drained.put(codeFromKey(reply.get(i)), count);
      }
    }
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
