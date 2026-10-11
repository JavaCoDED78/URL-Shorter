package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.cache.CachedLink;
import com.javaded78.urlshorter.domain.exception.LinkExpiredException;
import com.javaded78.urlshorter.domain.exception.LinkNotFoundException;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.model.LinkStats;
import com.javaded78.urlshorter.domain.model.ResolvedLink;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReadLinkService}: the hot request-side path — cache-aside redirect,
 * expiration re-check, pending-counter stats and the read lookup. Write-side behaviour (create,
 * rate limiting) is covered by {@link WriteLinkServiceTest}.
 */
class ReadLinkServiceTest {

  private final ShortenerProperties properties =
      new ShortenerProperties(
          new ShortenerProperties.Url(2048, List.of("http", "https")),
          new ShortenerProperties.Alias(64, Set.of("api")),
          new ShortenerProperties.Create(3),
          new ShortenerProperties.Cache(java.time.Duration.ofHours(1), java.time.Duration.ofSeconds(45), 1_000_000L, 0.0001),
          new ShortenerProperties.Code(7),
          org.springframework.util.unit.DataSize.ofKilobytes(16),
          1024);

  private final com.javaded78.urlshorter.domain.repository.LinkRepository repository =
      mock(com.javaded78.urlshorter.domain.repository.LinkRepository.class);
  private final FakeLinkCache linkCache = new FakeLinkCache();
  private final com.javaded78.urlshorter.domain.counter.ClickCounter clickCounter =
      mock(com.javaded78.urlshorter.domain.counter.ClickCounter.class);

  /** The fixed reference instant all resolve/expiration assertions run against. */
  private static final Instant NOW = Instant.parse("2025-06-01T00:00:00Z");

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final ReadLinkService service =
      new ReadLinkService(repository, linkCache, clickCounter, metricsStub(), clock);

  /** The metrics facade must pass calls through (timers wrap suppliers). */
  private static MetricsRecorder metricsStub() {
    MetricsRecorder metrics = mock(MetricsRecorder.class);
    org.mockito.Mockito.when(metrics.timeRedisLookup(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
    org.mockito.Mockito.when(metrics.timePostgresLookup(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
    org.mockito.Mockito.when(metrics.timeRedirect(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
    return metrics;
  }

  public ReadLinkServiceTest() {
    // FakeLinkCache starts empty, so the default resolve path is a cold miss.
  }

  @Test
  void returnsExistingLinkByCode() {
    Link link = new Link("abc1234", "https://example.com", null, 0, NOW);
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(Optional.of(link));

    Link result = service.getByCode("abc1234");

    assertThat(result.originalUrl()).isEqualTo("https://example.com");
  }

  @Test
  void throwsWhenLinkNotFound() {
    when(repository.findByCodeIgnoreCase("missing")).thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> service.getByCode("missing"))
        .isInstanceOf(LinkNotFoundException.class);
  }

  // --- resolve: lookup + expiration (Tasks 5.1, 5.2, 5.4) ---

  @Test
  void resolvesActiveLink() {
    Link link = new Link("abc1234", "https://example.com", NOW.plusSeconds(60), 0, NOW);
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(Optional.of(link));

    ResolvedLink result = service.resolve("abc1234");

    assertThat(result.originalUrl()).isEqualTo("https://example.com");
    verify(clickCounter).increment("abc1234");
    // The coalesce supplier ran exactly once for a single cold miss.
    verify(repository, times(1)).findByCodeIgnoreCase("abc1234");
  }

  @Test
  void resolveCountsClickOnCacheHit() {
    linkCache.put(new CachedLink("abc1234", "https://cached.example", null));

    service.resolve("abc1234");

    verify(clickCounter).increment("abc1234");
    // A stored positive entry is served from cache: the DB supplier never runs.
    verify(repository, never()).findByCodeIgnoreCase(any());
  }

  @Test
  void resolvesNeverExpiringLink() {
    Link link = new Link("abc1234", "https://example.com", null, 0, NOW);
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(Optional.of(link));

    assertThat(service.resolve("abc1234").originalUrl()).isEqualTo("https://example.com");
  }

  @Test
  void resolveServesCacheHitWithoutTouchingDb() {
    linkCache.put(new CachedLink("abc1234", "https://cached.example", null));

    ResolvedLink result = service.resolve("abc1234");

    assertThat(result.originalUrl()).isEqualTo("https://cached.example");
    verify(repository, never()).findByCodeIgnoreCase(any());
  }

  @Test
  void resolveThrowsGoneForExpiredCacheHit() {
    linkCache.put(new CachedLink("abc1234", "https://cached.example", NOW.minusSeconds(1)));

    assertThatThrownBy(() -> service.resolve("abc1234")).isInstanceOf(LinkExpiredException.class);
    verify(repository, never()).findByCodeIgnoreCase(any());
  }

  @Test
  void resolveThrowsNotFoundForUnknownCode() {
    when(repository.findByCodeIgnoreCase("missing")).thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> service.resolve("missing")).isInstanceOf(LinkNotFoundException.class);
  }

  @Test
  void resolveThrowsGoneForExpiredLink() {
    // A stored, already-expired entry is served, then rejected by the re-check.
    linkCache.put(new CachedLink("abc1234", "https://example.com", NOW.minusSeconds(1)));
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> service.resolve("abc1234")).isInstanceOf(LinkExpiredException.class);
    verify(clickCounter, never()).increment(any());
  }

  @Test
  void resolveDoesNotCountClickForExpiredCacheHit() {
    // A stored, already-expired entry is served by the cache, then rejected by the re-check.
    linkCache.put(new CachedLink("abc1234", "https://cached.example", NOW.minusSeconds(1)));

    assertThatThrownBy(() -> service.resolve("abc1234")).isInstanceOf(LinkExpiredException.class);
    verify(clickCounter, never()).increment(any());
  }

  @Test
  void resolveDoesNotCountClickForUnknownCode() {
    when(repository.findByCodeIgnoreCase("missing")).thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> service.resolve("missing")).isInstanceOf(LinkNotFoundException.class);
    verify(clickCounter, never()).increment(any());
  }

  // --- getStats: persisted DB count + pending Redis counter (Task 9.2) ---

  @Test
  void getStatsSumsPersistedAndPendingClicks() {
    Link link = new Link("abc1234", "https://example.com", null, 7, NOW);
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(Optional.of(link));
    when(clickCounter.get("abc1234")).thenReturn(3L);

    LinkStats stats = service.getStats("abc1234");

    assertThat(stats.code()).isEqualTo("abc1234");
    assertThat(stats.clickCount()).isEqualTo(10);
  }

  @Test
  void getStatsWithZeroClicks() {
    Link link = new Link("abc1234", "https://example.com", null, 0, NOW);
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(Optional.of(link));
    when(clickCounter.get("abc1234")).thenReturn(0L);

    assertThat(service.getStats("abc1234").clickCount()).isZero();
  }

  @Test
  void getStatsUsesStoredCodeForPendingLookup() {
    Link link = new Link("abc1234", "https://example.com", null, 5, NOW);
    when(repository.findByCodeIgnoreCase("ABC1234")).thenReturn(Optional.of(link));
    when(clickCounter.get("abc1234")).thenReturn(2L);

    LinkStats stats = service.getStats("ABC1234");

    assertThat(stats.code()).isEqualTo("abc1234");
    assertThat(stats.clickCount()).isEqualTo(7);
  }

  @Test
  void getStatsForMissingLinkThrowsNotFound() {
    when(repository.findByCodeIgnoreCase(any())).thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> service.getStats("missing1"))
        .isInstanceOf(LinkNotFoundException.class);
  }
}

/**
 * In-memory {@link com.javaded78.urlshorter.domain.cache.LinkCache} for {@link ReadLinkServiceTest}:
 * a single code-slot cache that mirrors the real cache-aside shape — {@link #store} populates the
 * slot, {@link #coalesce} serves from it on a hit and otherwise runs the miss supplier exactly once
 * (coalescing concurrent misses behind one in-flight lookup), so stampede singleflight is exercised
 * at the unit level.
 */
class FakeLinkCache implements com.javaded78.urlshorter.domain.cache.LinkCache {
  private final java.util.concurrent.ConcurrentMap<String, java.util.Optional<CachedLink>> store =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.CompletableFuture<java.util.Optional<CachedLink>>> inFlight =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Override
  public java.util.Optional<CachedLink> get(String code) {
    return store.get(com.javaded78.urlshorter.infra.redis.cache.RedisLinkCache.key(code));
  }

  @Override
  public java.util.Optional<CachedLink> coalesce(
      String code, java.util.function.Supplier<java.util.Optional<CachedLink>> miss) {
    String key = com.javaded78.urlshorter.infra.redis.cache.RedisLinkCache.key(code);
    java.util.Optional<CachedLink> cached = store.get(key);
    if (cached != null && cached.isPresent()) {
      return cached;
    }
    java.util.concurrent.CompletableFuture<java.util.Optional<CachedLink>> existing =
        inFlight.get(key);
    if (existing != null) {
      return existing.join();
    }
    // Single-flight: the miss supplier runs once across concurrent callers for the same code.
    java.util.concurrent.CompletableFuture<java.util.Optional<CachedLink>> future =
        java.util.concurrent.CompletableFuture.supplyAsync(() -> miss.get());
    inFlight.put(key, future);
    java.util.concurrent.atomic.AtomicReference<java.util.Optional<CachedLink>> resolved =
        new java.util.concurrent.atomic.AtomicReference<>();
    future.whenComplete(
        (value, error) -> {
          resolved.set(value);
          inFlight.remove(key, future);
        });
    java.util.Optional<CachedLink> joined = future.join();
    if (joined.isPresent()) {
      store.put(key, joined);
    }
    return joined;
  }

  @Override
  public void put(CachedLink link) {
    store.put(com.javaded78.urlshorter.infra.redis.cache.RedisLinkCache.key(link.code()),
        java.util.Optional.of(link));
  }

  @Override
  public void evict(String code) {
    store.remove(com.javaded78.urlshorter.infra.redis.cache.RedisLinkCache.key(code));
  }

  @Override
  public void markExists(String code) {
    // No-op: the FakeLinkCache has no Bloom filter layer.
  }
}
