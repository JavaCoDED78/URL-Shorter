package com.javaded78.urlshorter.infra.redis.counter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
 * unavailability must never surface to the caller, and a per-batch Redis outage mid-scan must not
 * drop counters that earlier (already-drained) batches returned.
 */
class RedisClickCounterTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  @SuppressWarnings("unchecked")
  private final Cursor<String> cursor = mock(Cursor.class);

  @SuppressWarnings("unchecked")
  private final DefaultRedisScript<List<String>> script = mock(DefaultRedisScript.class);

  private final com.javaded78.urlshorter.infra.redis.script.CounterDrainScript drainScript =
      mock(com.javaded78.urlshorter.infra.redis.script.CounterDrainScript.class);

  private final RedisClickCounter counter = new RedisClickCounter(redis, drainScript);

  RedisClickCounterTest() {
    when(redis.opsForValue()).thenReturn(ops);
    when(drainScript.batch()).thenReturn(script);
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
    when(ops.increment("clicks:abc1234", 5L))
        .thenThrow(new RedisConnectionFailureException("down"));

    assertDoesNotThrow(() -> counter.restore("abc1234", 5L));
  }

  @Test
  void deleteOnRedisOutageIsIgnored() {
    when(redis.delete("clicks:abc1234")).thenThrow(new RedisConnectionFailureException("down"));

    assertDoesNotThrow(() -> counter.delete("abc1234"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void drainAllParsesBatchReplyAndSkipsMissingAndZero() {
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    when(cursor.hasNext()).thenReturn(true, true, true, false);
    when(cursor.next()).thenReturn("clicks:good", "clicks:missing", "clicks:zero");

    // The batch script returns a flat [key, value, ...] array. "missing" is absent from the reply
    // (never existed / already drained); "zero" is present but drained to 0, so it must be skipped.
    when(redis.execute(any(), anyList()))
        .thenReturn(List.of("clicks:good", "12", "clicks:zero", "0"));

    Map<String, Long> drained = counter.drainAll();

    assertEquals(List.of("good"), new ArrayList<>(drained.keySet()));
    assertEquals(12L, drained.get("good"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void drainAllDrainsInBatchesOfBatchSize() {
    RedisClickCounter small = new RedisClickCounter(redis, drainScript, 2);
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    when(cursor.hasNext()).thenReturn(true, true, true, true, true, false);
    when(cursor.next()).thenReturn("clicks:a", "clicks:b", "clicks:c", "clicks:d", "clicks:e");

    AtomicInteger calls = new AtomicInteger();
    // Each batch returns its first key with a positive count, so we can see the batch boundaries.
    when(redis.execute(any(), anyList()))
        .thenAnswer(
            inv -> {
              calls.getAndIncrement();
              return List.of(String.valueOf(((List<?>) inv.getArgument(1)).get(0)), "5");
            });

    Map<String, Long> drained = small.drainAll();

    assertEquals(3, calls.get()); // 5 keys / batch 2 -> 3 batches (2, 2, 1)
    assertEquals(5L, drained.get("a"));
    assertEquals(5L, drained.get("c"));
    assertEquals(5L, drained.get("e"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void drainAllPerBatchFailureKeepsEarlierDrain() {
    RedisClickCounter small = new RedisClickCounter(redis, drainScript, 2);
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    when(cursor.hasNext()).thenReturn(true, true, true, false);
    when(cursor.next()).thenReturn("clicks:a", "clicks:b", "clicks:c");

    // First batch drains "a"; the second batch hits a dead Redis and throws.
    when(redis.execute(any(), anyList()))
        .thenReturn(List.of("clicks:a", "3"))
        .thenThrow(new RedisConnectionFailureException("down"));

    Map<String, Long> drained = small.drainAll();

    // Atomic batch: the failed batch left "c" intact in Redis, and the earlier drained "a" was not
    // lost — the fail-open policy holds.
    assertEquals(3L, drained.get("a"));
    assertFalse(drained.containsKey("c"));
  }

  @Test
  void drainAllIsEmptyWhenNoCountersExist() {
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    when(cursor.hasNext()).thenReturn(false);

    assertEquals(0, counter.drainAll().size());
  }
}
