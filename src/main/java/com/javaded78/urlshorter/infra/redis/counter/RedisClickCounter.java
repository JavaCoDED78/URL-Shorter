package com.javaded78.urlshorter.infra.redis.counter;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import com.javaded78.urlshorter.infra.redis.script.CounterDrainScript;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link ClickCounter}: {@code INCR clicks:{lowercaseCode}}.
 *
 * <p>Drain uses SCAN (not blocking KEYS) to find counters and executes the
 * {@link CounterDrainScript} per key — read-and-reset happens atomically
 * inside one Lua run.
 *
 * <p>Increment failure policy (Task 17.2): redirect availability wins. If
 * Redis is unavailable the click is dropped and counted via the
 * {@code clicks.increment.failed} metric — click counting is best-effort
 * analytics, the redirect itself is the product.
 */
@Component
public class RedisClickCounter implements ClickCounter {

    static final String KEY_PREFIX = "clicks:";

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RedisClickCounter.class);

    private final StringRedisTemplate redis;
    private final CounterDrainScript drainScript;
    private final MetricsRecorder metrics;

    public RedisClickCounter(StringRedisTemplate redis, CounterDrainScript drainScript,
            MetricsRecorder metrics) {
        this.redis = redis;
        this.drainScript = drainScript;
        this.metrics = metrics;
    }

    @Override
    public void increment(String code) {
        try {
            redis.opsForValue().increment(key(code));
            metrics.clickIncremented();
        } catch (org.springframework.dao.DataAccessException ex) {
            // Task 17.2: the redirect must survive a dead Redis; the click is
            // dropped and counted so the loss is observable.
            metrics.clickIncrementFailed();
            log.warn("click counter unavailable for {} — click dropped: {}", key(code), ex.toString());
        }
    }

    @Override
    public long get(String code) {
        String value = redis.opsForValue().get(key(code));
        return value == null ? 0 : Long.parseLong(value);
    }

    @Override
    public Map<String, Long> drainAll() {
        Map<String, Long> drained = new HashMap<>();
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String redisKey = cursor.next();
                Long value = redis.execute(drainScript.get(), List.of(redisKey));
                if (value != null && value > 0) {
                    drained.put(codeFromKey(redisKey), value);
                }
            }
        }
        return drained;
    }

    @Override
    public void restore(String code, long value) {
        redis.opsForValue().increment(key(code), value);
    }

    @Override
    public void delete(String code) {
        redis.delete(key(code));
    }

    static String key(String code) {
        return KEY_PREFIX + code.toLowerCase(Locale.ROOT);
    }

    private static String codeFromKey(String redisKey) {
        return redisKey.substring(KEY_PREFIX.length());
    }
}
