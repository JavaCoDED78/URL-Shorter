package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.config.AliasConfig;
import com.javaded78.urlshorter.domain.config.UrlConfig;
import com.javaded78.urlshorter.domain.config.WriteServiceConfig;
import com.javaded78.urlshorter.domain.exception.AliasAlreadyExistsException;
import com.javaded78.urlshorter.domain.exception.DuplicateCodeException;
import com.javaded78.urlshorter.domain.exception.LinkNotFoundException;
import com.javaded78.urlshorter.domain.exception.RateLimitExceededException;
import com.javaded78.urlshorter.domain.idempotency.IdempotencyStore;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.validation.AliasValidator;
import com.javaded78.urlshorter.domain.validation.UrlValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
          new ShortenerProperties.Cache(
              java.time.Duration.ofHours(1),
              java.time.Duration.ofSeconds(45),
              1_000_000L,
              0.0001,
              0.15),
          new ShortenerProperties.Code(7),
          new ShortenerProperties.Idempotency(
              java.time.Duration.ofHours(24), java.time.Duration.ofSeconds(5)),
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
  private final CreateRateLimitSource rateLimitSource = mock(CreateRateLimitSource.class);
  private final IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
  private final UrlValidator urlValidator =
      spy(
          new UrlValidator(
              new UrlConfig(properties.url().maxLength(), properties.url().allowedSchemes(), 8)));
  private final AliasValidator aliasValidator =
      spy(
          new AliasValidator(
              new AliasConfig(properties.alias().maxLength(), properties.alias().reserved())));
  private final Clock clock = Clock.fixed(Instant.parse("2025-06-01T00:00:00Z"), ZoneOffset.UTC);

  private final WriteLinkService service =
      new WriteLinkService(
          clock,
          repository,
          codeGenerator,
          urlValidator,
          aliasValidator,
          new WriteServiceConfig(properties.create().maxCollisionRetries()),
          linkCache,
          clickCounter,
          metricsStub(),
          rateLimiter,
          rateLimitSource,
          idempotencyStore);

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
    // default: rate limit admits everything, and the source reports a client key
    // (a denied probe is tested separately)
    when(rateLimiter.tryAcquire(any())).thenReturn(true);
    when(rateLimitSource.resolveClientKey()).thenReturn("203.0.113.9");
  }

  /** The fixed reference instant all tests run against (see {@link #clock}). */
  private static final Instant NOW = Instant.parse("2025-06-01T00:00:00Z");

  // --- create: generated code ---

  @Test
  void createsLinkWithGeneratedCode() {
    when(codeGenerator.generate()).thenReturn("abc1234");
    doNothing().when(repository).save(any(Link.class));

    Link link = service.create("https://example.com", null, null, null);

    assertThat(link.code()).isEqualTo("abc1234");
    assertThat(link.originalUrl()).isEqualTo("https://example.com");
    assertThat(link.clickCount()).isZero();
    assertThat(link.expiresAt()).isNull();
    assertThat(link.createdAt()).isNotNull();
  }

  @Test
  void retriesOnGeneratedCodeCollision() {
    when(codeGenerator.generate()).thenReturn("taken1", "fresh12");
    // Sequential per-call behavior: first save collides, second succeeds.
    // A void method can't be `when(...).thenThrow().thenAnswer()` chained, so
    // use an Answer instead of doThrow()/doNothing() (which would collapse to
    // the last registration and never throw).
    AtomicInteger saveCalls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (saveCalls.getAndIncrement() == 0) {
                throw new DuplicateCodeException("duplicate key");
              }
              return null;
            })
        .when(repository)
        .save(any(Link.class));

    Link link = service.create("https://example.com", null, null, null);

    assertThat(link.code()).isEqualTo("fresh12");
    verify(codeGenerator, times(2)).generate();
  }

  @Test
  void failsWhenCollisionRetriesAreExhausted() {
    when(codeGenerator.generate()).thenReturn("taken1");
    doThrow(new DuplicateCodeException("duplicate key")).when(repository).save(any(Link.class));

    assertThatThrownBy(() -> service.create("https://example.com", null, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("collision");
    verify(codeGenerator, times(3)).generate();
  }

  @Test
  void createIsDeniedWhenRateLimiterRejectsProbe() {
    when(rateLimiter.tryAcquire(any())).thenReturn(false);

    assertThatThrownBy(() -> service.create("https://example.com", null, null, null))
        .isInstanceOf(RateLimitExceededException.class);
    // A denied probe short-circuits before any other work: no validation, no insert.
    verify(repository, never()).existsByCode(any());
    verify(codeGenerator, never()).generate();
  }

  @Test
  void rateLimiterIsProbedBeforeValidation() {
    when(rateLimiter.tryAcquire(any())).thenReturn(false);

    assertThatThrownBy(() -> service.create("not-a-url", null, null, null))
        .isInstanceOf(RateLimitExceededException.class);
    verify(urlValidator, never()).validate(any());
  }

  @Test
  void createIsDeniedForAliasPathToo() {
    when(rateLimiter.tryAcquire(any())).thenReturn(false);

    assertThatThrownBy(() -> service.create("https://example.com", "my-alias", null, null))
        .isInstanceOf(RateLimitExceededException.class);
    verify(aliasValidator, never()).validateAndNormalize(any());
  }

  // --- create: alias ---

  @Test
  void createsLinkWithNormalizedAlias() {
    doNothing().when(repository).save(any(Link.class));

    Link link = service.create("https://example.com", "My-Link", null, null);

    assertThat(link.code()).isEqualTo("my-link");
  }

  @Test
  void rejectsAliasThatAlreadyExists() {
    when(repository.existsByCode("taken1")).thenReturn(true);

    assertThatThrownBy(() -> service.create("https://example.com", "taken1", null, null))
        .isInstanceOf(AliasAlreadyExistsException.class);
  }

  @Test
  void rejectsAliasThatWinsTheRaceBetweenCheckAndInsert() {
    when(repository.existsByCode("racer1")).thenReturn(false);
    doThrow(new DuplicateCodeException("duplicate key")).when(repository).save(any(Link.class));

    assertThatThrownBy(() -> service.create("https://example.com", "racer1", null, null))
        .isInstanceOf(AliasAlreadyExistsException.class);
  }

  @Test
  void rejectsInvalidOriginalUrl() {
    assertThatThrownBy(() -> service.create("not-a-url", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsReservedAlias() {
    assertThatThrownBy(() -> service.create("https://example.com", "api", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsExpirationInThePast() {
    assertThatThrownBy(
            () -> service.create("https://example.com", null, NOW.minusSeconds(60), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expiresAt");
    verify(repository, never()).save(any(Link.class));
  }

  @Test
  void rejectsExpirationEqualToNow() {
    assertThatThrownBy(() -> service.create("https://example.com", null, NOW, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- delete: cache eviction + counter cleanup (Tasks 10.1-10.3) ---

  @Test
  void deleteEvictsCacheAfterCommitNotBefore() {
    Link link = new Link("abc1234", "https://example.com", null, 0, NOW);
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(Optional.of(link));

    try (MockedStatic<TransactionSynchronizationManager> tx =
        mockStatic(TransactionSynchronizationManager.class)) {
      service.delete("abc1234");

      // The pre-delete evict happened once. Crucially the second evict must NOT have fired yet:
      // it is deferred to afterCommit, so a racing redirect that read the still-committed row
      // between this evict and the commit cannot leave a stale entry behind.
      verify(linkCache, times(1)).evict("abc1234");

      ArgumentCaptor<TransactionSynchronization> captor =
          ArgumentCaptor.forClass(TransactionSynchronization.class);
      tx.verify(() -> TransactionSynchronizationManager.registerSynchronization(captor.capture()));

      // Firing the commit callback is what actually evicts the repopulated cache entry.
      captor.getValue().afterCommit();
      verify(linkCache, times(2)).evict("abc1234");
    }
  }

  @Test
  void deleteUsesStoredLowercaseCode_evictAfterCommit() {
    Link link = new Link("abc1234", "https://example.com", null, 0, NOW);
    when(repository.findByCodeIgnoreCase("ABC1234")).thenReturn(Optional.of(link));

    try (MockedStatic<TransactionSynchronizationManager> tx =
        mockStatic(TransactionSynchronizationManager.class)) {
      service.delete("ABC1234");

      verify(repository).deleteByCode("abc1234");
      verify(linkCache, times(1)).evict("abc1234");

      ArgumentCaptor<TransactionSynchronization> captor =
          ArgumentCaptor.forClass(TransactionSynchronization.class);
      tx.verify(() -> TransactionSynchronizationManager.registerSynchronization(captor.capture()));
      captor.getValue().afterCommit();
      verify(linkCache, times(2)).evict("abc1234");
    }
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
    Link link = new Link("abc1234", "https://example.com", null, 0, NOW);
    when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(Optional.of(link));
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
