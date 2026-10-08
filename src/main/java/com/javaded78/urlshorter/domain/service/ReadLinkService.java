package com.javaded78.urlshorter.domain.service;

import com.javaded78.urlshorter.domain.cache.CachedLink;
import com.javaded78.urlshorter.domain.cache.LinkCache;
import com.javaded78.urlshorter.domain.counter.ClickCounter;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.model.LinkStats;
import com.javaded78.urlshorter.domain.model.ResolvedLink;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.infra.persistence.LinkEntity;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Read path for short links (CQRS-lite split from {@link LinkService}).
 *
 * <p>This is the hot path: {@link #redirect(code)} answers 302 and is taken on every request, so it
 * owns the cache-aside lookups, the {@code expiresAt} re-check on cache hits and the click
 * increment. {@link #get(code)} and {@link #getStats(code)} are read-only queries over the same
 * cache-insensitive code space.
 *
 * <p>Intentionally does NOT know about create, rate-limiting, URL/alias validation, collision
 * retries or transactional inserts — those live in {@link WriteLinkService}. Keeping the read and
 * write responsibilities in separate beans decouples the hot redirect path (cache + counter +
 * metrics) from the write path (validation + DB writes), so a hot read never pays for a rate-limit
 * probe and the two paths evolve independently.
 *
 * <p>All time is sourced from an injected {@link Clock} ({@code clock.instant()}), never
 * {@code Instant.now()} directly, so the {@code expiresAt} re-checks in {@code resolve} are
 * deterministic in tests.
 *
 * <p>Throws {@link LinkNotFoundException} (404) and {@link LinkExpiredException} (410); both are
 * reported to {@code MetricsRecorder}.
 */
@Service
public class ReadLinkService {

  private static final Logger log = LoggerFactory.getLogger(ReadLinkService.class);

  private final LinkRepository repository;
  private final LinkCache linkCache;
  private final ClickCounter clickCounter;
  private final MetricsRecorder metrics;
  private final Clock clock;

  public ReadLinkService(
      LinkRepository repository,
      LinkCache linkCache,
      ClickCounter clickCounter,
      MetricsRecorder metrics,
      Clock clock) {
    this.repository = repository;
    this.linkCache = linkCache;
    this.clickCounter = clickCounter;
    this.metrics = metrics;
    this.clock = clock;
  }

  /**
   * Resolves a code for redirection (Tasks 5.1, 5.2, 5.4; cache-aside from Sprint 6, Task 6.3) and
   * times the end-to-end redirect (controller → service) behind {@code
   * MetricsRecorder#redirectDuration}.
   *
   * @throws LinkNotFoundException if no such link exists (404)
   * @throws LinkExpiredException if the link's {@code expiresAt} has passed (410)
   */
  public ResolvedLink redirect(String code) {
    return metrics.timeRedirect(() -> resolve(code));
  }

  /**
   * Returns the link with the given code (persisted counts only — no pending Redis clicks).
   *
   * @throws LinkNotFoundException if no such link exists
   */
  public Link getByCode(String code) {
    return repository.findByCodeIgnoreCase(code)
        .map(this::toDomain)
        .orElseThrow(() -> notFound(code));
  }

  /**
   * Click statistics for a code (Task 9.2): persisted DB count plus the pending Redis counter — the
   * logically current value, without waiting for the flush interval. Lookup is case-insensitive
   * like redirects, because counters are keyed by the stored (lowercase) code.
   *
   * @throws LinkNotFoundException if no such link exists
   */
  public LinkStats getStats(String code) {
    Link link =
        repository.findByCodeIgnoreCase(code).map(this::toDomain).orElseThrow(() -> notFound(code));
    long pending = clickCounter.get(link.code());
    return new LinkStats(link.code(), link.clickCount() + pending);
  }

  /**
   * Resolves a code for redirection (Tasks 5.1, 5.2, 5.4; cache-aside from Sprint 6, Task 6.3):
   *
   * <pre>
   * cache.get(code)
   *   ├─ HIT  → re-check expiresAt from the cached value → redirect target
   *   └─ MISS → PostgreSQL (case-insensitive) → 404 / 410
   *             → active: cache.put(min(cacheTtl, remainingLifetime)) → target
   * </pre>
   *
   * <p>Expiration is business logic, not a Redis TTL side effect: the cached value carries {@code
   * expiresAt} and it is validated on every hit, so a link never stays usable because its cache
   * entry outlived it.
   *
   * @throws LinkNotFoundException if no such link exists (404)
   * @throws LinkExpiredException if the link's {@code expiresAt} has passed (410)
   */
  public ResolvedLink resolve(String code) {
    Optional<CachedLink> cached = metrics.timeRedisLookup(() -> linkCache.get(code));
    if (cached.isPresent()) {
      metrics.cacheHit();
      CachedLink hit = cached.get();
      if (hit.isExpired(clock.instant())) {
        metrics.linkExpired();
        throw new LinkExpiredException(code);
      }
      // Click counts only after the redirect target is validated (Task 7.3).
      recordIncrement(code);
      metrics.linkRedirected();
      return new ResolvedLink(hit.code(), hit.originalUrl());
    }
    metrics.cacheMiss();
    Link link =
        metrics.timePostgresLookup(
            () ->
                repository
                    .findByCodeIgnoreCase(code)
                    .map(this::toDomain)
                    .orElseThrow(() -> notFound(code)));
    if (link.isExpired(clock.instant())) {
      // Expired links are never cached (Task 6.4) and never counted.
      metrics.linkExpired();
      throw new LinkExpiredException(code);
    }
    linkCache.put(new CachedLink(link.code(), link.originalUrl(), link.expiresAt()));
    recordIncrement(code);
    metrics.linkRedirected();
    return new ResolvedLink(link.code(), link.originalUrl());
  }

  /**
   * Increments the click counter and reports the outcome via {@code MetricsRecorder}: the increment
   * is a Redis operation whose availability is not guaranteed, so the caller owns both the success
   * ({@code clicksIncremented}) and the loss ({@code clicksIncrementFailed}) metrics.
   */
  private void recordIncrement(String code) {
    if (!clickCounter.increment(code)) {
      metrics.clickIncrementFailed();
    } else {
      metrics.clickIncremented();
    }
  }

  private LinkNotFoundException notFound(String code) {
    metrics.linkNotFound();
    return new LinkNotFoundException(code);
  }

  private Link toDomain(LinkEntity entity) {
    return new Link(
        entity.getCode(),
        entity.getOriginalUrl(),
        entity.getExpiresAt(),
        entity.getClickCount(),
        entity.getCreatedAt());
  }
}
