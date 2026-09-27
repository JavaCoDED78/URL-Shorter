package com.javaded78.urlshorter.infra.redis.counter;

import java.util.Locale;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link ClickCounter}: {@code INCR clicks:{lowercaseCode}}.
 */
@Component
public class RedisClickCounter implements ClickCounter {

    static final String KEY_PREFIX = "clicks:";

    private final StringRedisTemplate redis;

    public RedisClickCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void increment(String code) {
        redis.opsForValue().increment(key(code));
    }

    static String key(String code) {
        return KEY_PREFIX + code.toLowerCase(Locale.ROOT);
    }
}
