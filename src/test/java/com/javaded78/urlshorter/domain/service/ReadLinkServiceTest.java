package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.cache.CachedLink;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.model.LinkStats;
import com.javaded78.urlshorter.domain.model.ResolvedLink;
import com.javaded78.urlshorter.infra.persistence.LinkEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

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
          new ShortenerProperties.Cache(java.time.Duration.ofHours(1)),
          new ShortenerProperties.Code(7),
          org.springframework.util.unit.DataSize.ofKilobytes(16),
          1024);

  private final com.javaded78.urlshorter.domain.repository.LinkRepository repository =
      mock(com.javaded78.urlshorter.domain.repository.LinkRepository.class);
  private final com.javaded78.urlshorter.domain.cache.LinkCache linkCache =
      mock(com.javaded78.urlshorter.domain.cache.LinkCache.class);
  private final com.javaded78.urlshorter.domain.counter.ClickCounter clickCounter =
      mock(com.javaded78.urlshorter.domain.counter.ClickCounter.class);

  /** The fixed reference instant all resolve/expiration assertions run against. */
  private static final Instant NOW = Instant.parse("2025-06-01T00:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final ReadLinkService service =
      new ReadLinkService(
          repository,
          linkCache,
          clickCounter,
          metricsStub(),
          clock);

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
    // default: cache misses everywhere; cache-aware tests override
    when(linkCache.get(any())).thenReturn(java.util.Optional.empty());
  }

  private static LinkEntity persisted(LinkEntity entity) {
    entity.setCreatedAt(Instant.now());
    entity.setUpdatedAt(Instant.now());
    return entity;
  }

  @Test
  void returnsExistingLinkByCode() {
    LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com", null));
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.of(entity));

    Link link = service.getByCode("abc1234");

    assertThat(link.originalUrl()).isEqualTo("https://example.com");
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
    LinkEntity entity =
        persisted(new LinkEntity("abc1234", "https://example.com", NOW.plusSeconds(60)));
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.of(entity));

    ResolvedLink link = service.resolve("abc1234");

    assertThat(link.originalUrl()).isEqualTo("https://example.com");
    verify(linkCache)
        .put(
            new CachedLink(
                "abc1234", "https://example.com", entity.getExpiresAt()));
    verify(clickCounter).increment("abc1234");
  }

  @Test
  void resolveCountsClickOnCacheHit() {
    when(linkCache.get("abc1234"))
        .thenReturn(
            java.util.Optional.of(
                new CachedLink("abc1234", "https://cached.example", null)));

    service.resolve("abc1234");

    verify(clickCounter).increment("abc1234");
  }

  @Test
  void resolvesNeverExpiringLink() {
    LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com", null));
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.of(entity));

    assertThat(service.resolve("abc1234").originalUrl()).isEqualTo("https://example.com");
  }

  @Test
  void resolveServesCacheHitWithoutTouchingDb() {
    when(linkCache.get("abc1234"))
        .thenReturn(
            java.util.Optional.of(
                new CachedLink("abc1234", "https://cached.example", null)));

    ResolvedLink link = service.resolve("abc1234");

    assertThat(link.originalUrl()).isEqualTo("https://cached.example");
    verify(repository, never()).findByCodeIgnoreCase(any());
    verify(linkCache, never()).put(any());
  }

  @Test
  void resolveThrowsGoneForExpiredCacheHit() {
    when(linkCache.get("abc1234"))
        .thenReturn(
            java.util.Optional.of(
                new CachedLink("abc1234", "https://cached.example", NOW.minusSeconds(1))));

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
    LinkEntity entity =
        persisted(new LinkEntity("abc1234", "https://example.com", NOW.minusSeconds(1)));
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.of(entity));

    assertThatThrownBy(() -> service.resolve("abc1234")).isInstanceOf(LinkExpiredException.class);
    verify(linkCache, never()).put(any());
    verify(clickCounter, never()).increment(any());
  }

  @Test
  void resolveDoesNotCountClickForExpiredCacheHit() {
    when(linkCache.get("abc1234"))
        .thenReturn(
            java.util.Optional.of(
                new CachedLink("abc1234", "https://cached.example", NOW.minusSeconds(1))));

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
    LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com", null));
    entity.setClickCount(7);
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.of(entity));
    when(clickCounter.get("abc1234")).thenReturn(3L);

    LinkStats stats = service.getStats("abc1234");

    assertThat(stats.code()).isEqualTo("abc1234");
    assertThat(stats.clickCount()).isEqualTo(10);
  }

  @Test
  void getStatsWithZeroClicks() {
    LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com", null));
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.of(entity));
    when(clickCounter.get("abc1234")).thenReturn(0L);

    assertThat(service.getStats("abc1234").clickCount()).isZero();
  }

  @Test
  void getStatsUsesStoredCodeForPendingLookup() {
    LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com", null));
    entity.setClickCount(5);
    when(repository.findByCodeIgnoreCase("ABC1234")).thenReturn(java.util.Optional.of(entity));
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
