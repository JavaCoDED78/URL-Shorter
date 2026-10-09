package com.javaded78.urlshorter.infra.redis.counter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Counter key model (Task 7.1): {@code clicks:{lowercaseCode}} holding a plain integer — string
 * counters via INCR, no hash-based model, so the flush can drain atomically.
 *
 * <p>The tests in this class cover the fail-open failure policies (Tasks 17.1/17.2/17.3): Redis
 * unavailability must never surface to the caller, and a per-key Redis outage mid-scan must not
 * drop counters that were already drained.
 */
class RedisClickCounterTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  @SuppressWarnings("unchecked")
  private final Cursor<String> cursor = mock(Cursor.class);

  @SuppressWarnings("unchecked")
  private final DefaultRedisScript<Long> script = mock(DefaultRedisScript.class);

  private final com.javaded78.urlshorter.infra.redis.script.CounterDrainScript drainScript =
      mock(com.javaded78.urlshorter.infra.redis.script.CounterDrainScript.class);

  private final RedisClickCounter counter = new RedisClickCounter(redis, drainScript);

  RedisClickCounterTest() {
    when(redis.opsForValue()).thenReturn(ops);
    when(drainScript.get()).thenReturn(script);
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

  @Test
  void nonNumericCounterValueReadsAsZero() {
    when(ops.get("clicks:abc1234")).thenReturn("not-a-number");

    assertEquals(0L, counter.get("abc1234"));
  }

  @Test
  void getOnRedisOutageReadsAsZero() {
    when(ops.get("clicks:abc1234")).thenThrow(new RedisConnectionFailureException("down"));

    assertEquals(0L, counter.get("abc1234"));
  }

  @Test
  void incrementOnRedisOutageFailsOpen() {
    when(ops.increment("clicks:abc1234")).thenThrow(new RedisConnectionFailureException("down"));

    assertFalse(counter.increment("abc1234"));
  }

  @Test
  void restoreOnRedisOutageIsIgnored() {
    when(ops.increment("clicks:abc1234", 5L)).thenThrow(new RedisConnectionFailureException("down"));

    assertDoesNotThrow(() -> counter.restore("abc1234", 5L));
  }

  @Test
  void deleteOnRedisOutageIsIgnored() {
    when(redis.delete("clicks:abc1234")).thenThrow(new RedisConnectionFailureException("down"));

    assertDoesNotThrow(() -> counter.delete("abc1234"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void drainAllSkipsPerKeyOutageAndKeepsScanGoing() throws Exception {
    ScanOptions options = ScanOptions.scanOptions().build();
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    when(cursor.hasNext()).thenReturn(true, true, false);
    when(cursor.next()).thenReturn("clicks:good", "clicks:bad");
    when(redis.execute(any(), anyList()))
        .thenReturn(10L)
        .thenThrow(new RedisConnectionFailureException("down"));

    Map<String, Long> drained = counter.drainAll();

    assertEquals(List.of("good"), new java.util.ArrayList<>(drained.keySet()));
    assertEquals(10L, drained.get("good"));
  }
}
