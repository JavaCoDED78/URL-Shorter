package com.javaded78.urlshorter.infra.redis.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.cache.CachedLink;
import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisLinkCacheTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final CodeBloomFilter bloom = mock(CodeBloomFilter.class);
  private final RedisNegativeCache negativeCache = mock(RedisNegativeCache.class);
  private final MetricsRecorder metrics = mock(MetricsRecorder.class);

  private final ShortenerProperties properties =
      new ShortenerProperties(
          new ShortenerProperties.Url(2048, List.of("http", "https")),
          new ShortenerProperties.Alias(64, Set.of("api")),
          new ShortenerProperties.Create(5),
          // Jitter disabled here so the configured-TTL assertions below stay deterministic —
          // jitter itself is covered by CacheTtlPolicyTest.
          new ShortenerProperties.Cache(
              Duration.ofHours(1), Duration.ofSeconds(45), 1_000_000L, 0.0001, 0d),
          new ShortenerProperties.Code(7),
          new ShortenerProperties.Idempotency(
              java.time.Duration.ofHours(24), java.time.Duration.ofSeconds(5)),
          org.springframework.util.unit.DataSize.ofKilobytes(16),
          1024);

  private final RedisLinkCache cache =
      new RedisLinkCache(
          redis, objectMapper, properties, Clock.systemUTC(), bloom, negativeCache, metrics);

  RedisLinkCacheTest() {
    when(redis.opsForValue()).thenReturn(ops);
    // Real links are present in the filter by default; the negative marker is absent by default.
    when(bloom.contains(any())).thenReturn(true);
    when(negativeCache.isAbsent(any())).thenReturn(false);
  }

  @Test
  void getReturnsEmptyOnMiss() {
    when(ops.get("link:abc1234")).thenReturn(null);

    assertThat(cache.get("abc1234")).isEmpty();
  }

  @Test
  void getDeserializesHit() throws Exception {
    CachedLink value = new CachedLink("abc1234", "https://example.com", null);
    when(ops.get("link:abc1234")).thenReturn(objectMapper.writeValueAsString(value));

    Optional<CachedLink> result = cache.get("abc1234");

    assertThat(result).contains(value);
  }

  @Test
  void getTreatsInvalidJsonAsMissAndEvicts() {
    when(ops.get("link:abc1234")).thenReturn("not-json{{{");

    assertThat(cache.get("abc1234")).isEmpty();
    verify(redis).delete("link:abc1234");
  }

  @Test
  void getIsCaseInsensitiveOnKeys() {
    when(ops.get("link:abc1234")).thenReturn(null);

    cache.get("ABC1234");

    verify(ops).get("link:abc1234");
  }

  @Test
  void putStoresWithConfiguredTtlForNeverExpiringLink() throws Exception {
    cache.put(new CachedLink("abc1234", "https://example.com", null));

    verify(ops).set(eq("link:abc1234"), anyString(), eq(Duration.ofHours(1)));
  }

  @Test
  void putStoresWithRemainingLifetimeForExpiringLink() throws Exception {
    Instant expiresAt = Instant.now().plusSeconds(30);

    cache.put(new CachedLink("abc1234", "https://example.com", expiresAt));

    verify(ops).set(eq("link:abc1234"), anyString(), any(Duration.class));
  }

  @Test
  void putSkipsAlreadyExpiredLink() {
    cache.put(new CachedLink("abc1234", "https://example.com", Instant.now().minusSeconds(1)));

    verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void evictDeletesPositiveKey() {
    cache.evict("abc1234");

    verify(redis).delete("link:abc1234");
  }

  @Test
  void markExistsBubblesIntoFilterAndDropsNegativeMarker() {
    cache.markExists("abc1234");

    verify(bloom).markExists("abc1234");
    verify(negativeCache).evict("abc1234");
  }

  // --- coalesce stampede + negative caching ---

  @Test
  void coalesceReturnsRedisPositiveHitWithoutRunningMiss() throws Exception {
    CachedLink value = new CachedLink("abc1234", "https://example.com", null);
    when(ops.get("link:abc1234")).thenReturn(objectMapper.writeValueAsString(value));

    Optional<CachedLink> result = cache.coalesce("abc1234", RedisLinkCacheTest::mustNotRun);

    assertThat(result).contains(value);
  }

  @Test
  void coalesceStoresPositiveMissResult() {
    when(ops.get("link:abc1234")).thenReturn(null);
    when(bloom.contains("abc1234")).thenReturn(true);
    when(negativeCache.isAbsent("abc1234")).thenReturn(false);
    CachedLink link = new CachedLink("abc1234", "https://example.com", null);

    Optional<CachedLink> result = cache.coalesce("abc1234", () -> Optional.of(link));

    assertThat(result).contains(link);
    // Stored to Redis with the positive (never-expiring) TTL.
    verify(ops).set(eq("link:abc1234"), anyString(), eq(Duration.ofHours(1)));
  }

  @Test
  void coalesceMarksWhenMissReturnsEmpty() {
    when(ops.get("link:ghost1")).thenReturn(null);
    when(bloom.contains("ghost1")).thenReturn(true);
    when(negativeCache.isAbsent("ghost1")).thenReturn(false);

    Optional<CachedLink> result = cache.coalesce("ghost1", () -> Optional.empty());

    assertThat(result).isEmpty();
    // The short-TTL negative marker was recorded for the absent code (ADR-011).
    verify(negativeCache).markAbsent("ghost1");
  }

  @Test
  void coalesceShortCircuitsWhenBloomAbsent() {
    when(bloom.contains("ghost1")).thenReturn(false);

    Optional<CachedLink> result = cache.coalesce("ghost1", RedisLinkCacheTest::mustNotRun);

    assertThat(result).isEmpty();
  }

  @Test
  void coalesceShortCircuitsWhenNegativeMarkerPresent() {
    when(ops.get("link:ghost1")).thenReturn(null);
    when(bloom.contains("ghost1")).thenReturn(true);
    when(negativeCache.isAbsent("ghost1")).thenReturn(true);

    Optional<CachedLink> result = cache.coalesce("ghost1", RedisLinkCacheTest::mustNotRun);

    assertThat(result).isEmpty();
  }

  @Test
  void coalesceCoalescesConcurrentMissesIntoSingleLookup() throws InterruptedException {
    // Back the mock Redis with a real map so a late caller's initial get() finds the value the
    // owner put into the cache (a bare when(...).thenReturn(null) never would).
    Map<String, String> backing = new ConcurrentHashMap<>();
    when(ops.get(anyString())).thenAnswer(inv -> backing.get(inv.getArgument(0)));
    doAnswer(
            inv -> {
              backing.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(ops)
        .set(anyString(), anyString(), any(Duration.class));
    when(bloom.contains("abc1234")).thenReturn(true);
    when(negativeCache.isAbsent("abc1234")).thenReturn(false);
    CachedLink link = new CachedLink("abc1234", "https://example.com", null);
    // The miss supplier runs exactly once no matter how many callers coalesce onto it.
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    Supplier<Optional<CachedLink>> miss =
        () -> {
          try {
            start.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          calls.incrementAndGet();
          return Optional.of(link);
        };

    int threads = 50;
    CountDownLatch done = new CountDownLatch(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Optional<CachedLink>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                results.add(cache.coalesce("abc1234", miss));
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
      for (Optional<CachedLink> r : results) {
        assertThat(r).contains(link);
      }
      assertThat(calls.get()).isOne();
    } finally {
      pool.shutdownNow();
    }
  }

  static <T> Optional<T> mustNotRun() {
    throw new AssertionError("miss supplier must not have run");
  }
}
