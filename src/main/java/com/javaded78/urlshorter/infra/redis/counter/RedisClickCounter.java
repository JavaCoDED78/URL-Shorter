package com.javaded78.urlshorter.infra.redis.counter;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 */
@Component
public class RedisClickCounter implements ClickCounter {

    static final String KEY_PREFIX = "clicks:";

    private final StringRedisTemplate redis;
    private final CounterDrainScript drainScript;

    public RedisClickCounter(StringRedisTemplate redis, CounterDrainScript drainScript) {
        this.redis = redis;
        this.drainScript = drainScript;
    }

    @Override
    public void increment(String code) {
        redis.opsForValue().increment(key(code));
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
