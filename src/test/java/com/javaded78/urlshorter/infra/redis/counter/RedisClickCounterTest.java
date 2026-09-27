package com.javaded78.urlshorter.infra.redis.counter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Counter key model (Task 7.1): {@code clicks:{lowercaseCode}} holding a
 * plain integer — string counters via INCR, no hash-based model, so the
 * flush can drain atomically.
 */
class RedisClickCounterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final com.javaded78.urlshorter.infra.redis.script.CounterDrainScript drainScript =
            mock(com.javaded78.urlshorter.infra.redis.script.CounterDrainScript.class);

    private final RedisClickCounter counter = new RedisClickCounter(redis, drainScript);

    RedisClickCounterTest() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void incrementsCounterKey() {
        counter.increment("abc1234");

        verify(ops).increment("clicks:abc1234");
    }

    @Test
    void normalizesCodeToLowercase() {
        counter.increment("ABC1234");

        verify(ops).increment("clicks:abc1234");
    }
}
