package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.infra.persistence.LinkEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link WriteLinkService}: the request-side path — URL/alias validation, rate
 * limiting, collision retries, transactional inserts and the cache/counter eviction around delete.
 * Read-side behaviour (redirect/getStats) is covered by {@link ReadLinkServiceTest}.
 */
class WriteLinkServiceTest {

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
  private final CodeGenerator codeGenerator = mock(CodeGenerator.class);
  private final com.javaded78.urlshorter.domain.cache.LinkCache linkCache =
      mock(com.javaded78.urlshorter.domain.cache.LinkCache.class);
  private final com.javaded78.urlshorter.domain.counter.ClickCounter clickCounter =
      mock(com.javaded78.urlshorter.domain.counter.ClickCounter.class);
  private final RateLimiter rateLimiter = mock(RateLimiter.class);
  private final UrlValidator urlValidator = spy(new UrlValidator(properties));
  private final AliasValidator aliasValidator = spy(new AliasValidator(properties));
  private final Clock clock = Clock.fixed(Instant.parse("2025-06-01T00:00:00Z"), ZoneOffset.UTC);

  private final WriteLinkService service =
      new WriteLinkService(
          clock,
          repository,
          codeGenerator,
          urlValidator,
          aliasValidator,
          properties,
          linkCache,
          clickCounter,
          metricsStub(),
          rateLimiter);

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

  public WriteLinkServiceTest() {
    // default: rate limit admits everything (a denied probe is tested separately)
    when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
  }

  private static LinkEntity persisted(LinkEntity entity) {
    entity.setCreatedAt(Instant.now());
    entity.setUpdatedAt(Instant.now());
    return entity;
  }

  /** The fixed reference instant all tests run against (see {@link #clock}). */
  private static final Instant NOW = Instant.parse("2025-06-01T00:00:00Z");

  // --- create: generated code ---

  @Test
  void createsLinkWithGeneratedCode() {
    when(codeGenerator.generate()).thenReturn("abc1234");
    when(repository.saveAndFlush(any(LinkEntity.class)))
        .thenAnswer(invocation -> persisted(invocation.getArgument(0)));

    Link link = service.create("https://example.com", null, null);

    assertThat(link.code()).isEqualTo("abc1234");
    assertThat(link.originalUrl()).isEqualTo("https://example.com");
    assertThat(link.clickCount()).isZero();
    assertThat(link.expiresAt()).isNull();
    assertThat(link.createdAt()).isNotNull();
  }

  @Test
  void retriesOnGeneratedCodeCollision() {
    when(codeGenerator.generate()).thenReturn("taken1", "fresh12");
    when(repository.saveAndFlush(any(LinkEntity.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"))
        .thenAnswer(invocation -> persisted(invocation.getArgument(0)));

    Link link = service.create("https://example.com", null, null);

    assertThat(link.code()).isEqualTo("fresh12");
    verify(codeGenerator, times(2)).generate();
  }

  @Test
  void failsWhenCollisionRetriesAreExhausted() {
    when(codeGenerator.generate()).thenReturn("taken1");
    when(repository.saveAndFlush(any(LinkEntity.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(() -> service.create("https://example.com", null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("collision");
    verify(codeGenerator, times(3)).generate();
  }

  @Test
  void createIsDeniedWhenRateLimiterRejectsProbe() {
    when(rateLimiter.tryAcquire("create")).thenReturn(false);

    assertThatThrownBy(() -> service.create("https://example.com", null, null))
        .isInstanceOf(RateLimitExceededException.class);
    // A denied probe short-circuits before any other work: no validation, no insert.
    verify(repository, never()).existsByCode(any());
    verify(codeGenerator, never()).generate();
  }

  @Test
  void rateLimiterIsProbedBeforeValidation() {
    when(rateLimiter.tryAcquire("create")).thenReturn(false);

    assertThatThrownBy(() -> service.create("not-a-url", null, null))
        .isInstanceOf(RateLimitExceededException.class);
    verify(urlValidator, never()).validate(any());
  }

  @Test
  void createIsDeniedForAliasPathToo() {
    when(rateLimiter.tryAcquire("create")).thenReturn(false);

    assertThatThrownBy(() -> service.create("https://example.com", "my-alias", null))
        .isInstanceOf(RateLimitExceededException.class);
    verify(aliasValidator, never()).validateAndNormalize(any());
  }

  // --- create: alias ---

  @Test
  void createsLinkWithNormalizedAlias() {
    when(repository.saveAndFlush(any(LinkEntity.class)))
        .thenAnswer(invocation -> persisted(invocation.getArgument(0)));

    Link link = service.create("https://example.com", "My-Link", null);

    assertThat(link.code()).isEqualTo("my-link");
  }

  @Test
  void rejectsAliasThatAlreadyExists() {
    when(repository.existsByCode("taken1")).thenReturn(true);

    assertThatThrownBy(() -> service.create("https://example.com", "taken1", null))
        .isInstanceOf(AliasAlreadyExistsException.class);
  }

  @Test
  void rejectsAliasThatWinsTheRaceBetweenCheckAndInsert() {
    when(repository.existsByCode("racer1")).thenReturn(false);
    when(repository.saveAndFlush(any(LinkEntity.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(() -> service.create("https://example.com", "racer1", null))
        .isInstanceOf(AliasAlreadyExistsException.class);
  }

  @Test
  void rejectsInvalidOriginalUrl() {
    assertThatThrownBy(() -> service.create("not-a-url", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsReservedAlias() {
    assertThatThrownBy(() -> service.create("https://example.com", "api", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsExpirationInThePast() {
    assertThatThrownBy(() -> service.create("https://example.com", null, NOW.minusSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expiresAt");
    verify(repository, never()).saveAndFlush(any(LinkEntity.class));
  }

  @Test
  void rejectsExpirationEqualToNow() {
    assertThatThrownBy(() -> service.create("https://example.com", null, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- delete: cache eviction + counter cleanup (Tasks 10.1-10.3) ---

  @Test
  void deleteEvictsCacheAroundTheDatabaseDelete() {
    LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com", null));
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.of(entity));

    service.delete("abc1234");

    // Evict BEFORE the DB delete (a failed delete leaves the row alive and
    // the cache repopulates on the next read — self-healing), then evict
    // AGAIN after it to close the repopulation race window.
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(linkCache, clickCounter, repository);
    order.verify(linkCache).evict("abc1234");
    order.verify(clickCounter).delete("abc1234");
    order.verify(repository).deleteByCode("abc1234");
    order.verify(linkCache).evict("abc1234");
  }

  @Test
  void deleteUsesStoredLowercaseCode() {
    LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com", null));
    when(repository.findByCodeIgnoreCase("ABC1234")).thenReturn(java.util.Optional.of(entity));

    service.delete("ABC1234");

    verify(repository).deleteByCode("abc1234");
    verify(linkCache, org.mockito.Mockito.times(2)).evict("abc1234");
    verify(clickCounter).delete("abc1234");
  }

  @Test
  void deleteForMissingLinkThrowsNotFoundAndTouchesNothing() {
    when(repository.findByCodeIgnoreCase(any())).thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> service.delete("missing1")).isInstanceOf(LinkNotFoundException.class);
    verify(repository, never()).deleteByCode(any());
    verify(linkCache, never()).evict(any());
    verify(clickCounter, never()).delete(any());
  }

  @Test
  void deleteDoesNotEvictAgainWhenDatabaseDeleteFails() {
    LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com", null));
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.of(entity));
    org.mockito.Mockito.doThrow(new DataIntegrityViolationException("db down"))
        .when(repository)
        .deleteByCode("abc1234");

    assertThatThrownBy(() -> service.delete("abc1234"))
        .isInstanceOf(DataIntegrityViolationException.class);
    // Only the pre-delete evict happened; the row is still alive, so the
    // cache repopulates on the next read (self-healing, Task 10.2).
    verify(linkCache).evict("abc1234");
  }
}
