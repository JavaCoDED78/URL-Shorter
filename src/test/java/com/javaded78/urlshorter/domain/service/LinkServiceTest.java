package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.model.LinkStats;
import com.javaded78.urlshorter.infra.persistence.LinkEntity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class LinkServiceTest {

    private final ShortenerProperties properties = new ShortenerProperties(
            new ShortenerProperties.Url(2048, List.of("http", "https")),
            new ShortenerProperties.Alias(64, Set.of("api")),
            new ShortenerProperties.Create(3),
            new ShortenerProperties.Cache(java.time.Duration.ofHours(1)));

    private final com.javaded78.urlshorter.domain.repository.LinkRepository repository =
            mock(com.javaded78.urlshorter.domain.repository.LinkRepository.class);
    private final CodeGenerator codeGenerator = mock(CodeGenerator.class);
    private final com.javaded78.urlshorter.infra.redis.cache.LinkCache linkCache =
            mock(com.javaded78.urlshorter.infra.redis.cache.LinkCache.class);
    private final com.javaded78.urlshorter.infra.redis.counter.ClickCounter clickCounter =
            mock(com.javaded78.urlshorter.infra.redis.counter.ClickCounter.class);

    private final LinkService service = new LinkService(
            repository, codeGenerator, new UrlValidator(properties), new AliasValidator(properties),
            properties, linkCache, clickCounter, metricsStub());

    /** The metrics facade must pass calls through (timers wrap suppliers). */
    private static MetricsRecorder metricsStub() {
        MetricsRecorder metrics = mock(MetricsRecorder.class);
        org.mockito.Mockito.when(metrics.timeRedisLookup(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        org.mockito.Mockito.when(metrics.timePostgresLookup(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        org.mockito.Mockito.when(metrics.timeRedirect(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        return metrics;
    }

    public LinkServiceTest() {
        // default: cache misses everywhere; cache-aware tests override
        when(linkCache.get(any())).thenReturn(java.util.Optional.empty());
    }

    private static LinkEntity persisted(LinkEntity entity) {
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

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
        assertThatThrownBy(() -> service.create("https://example.com", null,
                Instant.now().minusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresAt");
        verify(repository, never()).saveAndFlush(any(LinkEntity.class));
    }

    @Test
    void rejectsExpirationEqualToNow() {
        assertThatThrownBy(() -> service.create("https://example.com", null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsExistingLinkByCode() {
        LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com", null));
        when(repository.findByCode("abc1234")).thenReturn(java.util.Optional.of(entity));

        Link link = service.getByCode("abc1234");

        assertThat(link.originalUrl()).isEqualTo("https://example.com");
    }

    @Test
    void throwsWhenLinkNotFound() {
        when(repository.findByCode("missing")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getByCode("missing"))
                .isInstanceOf(LinkNotFoundException.class);
    }

    // --- resolve: lookup + expiration (Tasks 5.1, 5.2, 5.4) ---

    @Test
    void resolvesActiveLink() {
        LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com",
                Instant.now().plusSeconds(60)));
        when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.of(entity));

        com.javaded78.urlshorter.domain.model.ResolvedLink link = service.resolve("abc1234");

        assertThat(link.originalUrl()).isEqualTo("https://example.com");
        verify(linkCache).put(new com.javaded78.urlshorter.infra.redis.cache.CachedLink(
                "abc1234", "https://example.com", entity.getExpiresAt()));
        verify(clickCounter).increment("abc1234");
    }

    @Test
    void resolveCountsClickOnCacheHit() {
        when(linkCache.get("abc1234")).thenReturn(java.util.Optional.of(
                new com.javaded78.urlshorter.infra.redis.cache.CachedLink(
                        "abc1234", "https://cached.example", null)));

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
        when(linkCache.get("abc1234")).thenReturn(java.util.Optional.of(
                new com.javaded78.urlshorter.infra.redis.cache.CachedLink(
                        "abc1234", "https://cached.example", null)));

        com.javaded78.urlshorter.domain.model.ResolvedLink link = service.resolve("abc1234");

        assertThat(link.originalUrl()).isEqualTo("https://cached.example");
        verify(repository, never()).findByCodeIgnoreCase(any());
        verify(linkCache, never()).put(any());
    }

    @Test
    void resolveThrowsGoneForExpiredCacheHit() {
        when(linkCache.get("abc1234")).thenReturn(java.util.Optional.of(
                new com.javaded78.urlshorter.infra.redis.cache.CachedLink(
                        "abc1234", "https://cached.example", Instant.now().minusSeconds(1))));

        assertThatThrownBy(() -> service.resolve("abc1234"))
                .isInstanceOf(LinkExpiredException.class);
        verify(repository, never()).findByCodeIgnoreCase(any());
    }

    @Test
    void resolveThrowsNotFoundForUnknownCode() {
        when(repository.findByCodeIgnoreCase("missing")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.resolve("missing"))
                .isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    void resolveThrowsGoneForExpiredLink() {
        LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com",
                Instant.now().minusSeconds(1)));
        when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.of(entity));

        assertThatThrownBy(() -> service.resolve("abc1234"))
                .isInstanceOf(LinkExpiredException.class);
        verify(linkCache, never()).put(any());
        verify(clickCounter, never()).increment(any());
    }

    @Test
    void resolveDoesNotCountClickForExpiredCacheHit() {
        when(linkCache.get("abc1234")).thenReturn(java.util.Optional.of(
                new com.javaded78.urlshorter.infra.redis.cache.CachedLink(
                        "abc1234", "https://cached.example", Instant.now().minusSeconds(1))));

        assertThatThrownBy(() -> service.resolve("abc1234"))
                .isInstanceOf(LinkExpiredException.class);
        verify(clickCounter, never()).increment(any());
    }

    @Test
    void resolveDoesNotCountClickForUnknownCode() {
        when(repository.findByCodeIgnoreCase("missing")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.resolve("missing"))
                .isInstanceOf(LinkNotFoundException.class);
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

        assertThatThrownBy(() -> service.delete("missing1"))
                .isInstanceOf(LinkNotFoundException.class);
        verify(repository, never()).deleteByCode(any());
        verify(linkCache, never()).evict(any());
        verify(clickCounter, never()).delete(any());
    }

    @Test
    void deleteDoesNotEvictAgainWhenDatabaseDeleteFails() {
        LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com", null));
        when(repository.findByCodeIgnoreCase("abc1234")).thenReturn(java.util.Optional.of(entity));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("db down"))
                .when(repository).deleteByCode("abc1234");

        assertThatThrownBy(() -> service.delete("abc1234"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Only the pre-delete evict happened; the row is still alive, so the
        // cache repopulates on the next read (self-healing, Task 10.2).
        verify(linkCache).evict("abc1234");
    }
}
