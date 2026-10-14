package com.javaded78.urlshorter.domain.service;

import com.javaded78.urlshorter.domain.cache.CachedLink;
import com.javaded78.urlshorter.domain.cache.LinkCache;
import com.javaded78.urlshorter.domain.counter.ClickCounter;
import com.javaded78.urlshorter.domain.exception.LinkExpiredException;
import com.javaded78.urlshorter.domain.exception.LinkNotFoundException;
import com.javaded78.urlshorter.domain.model.ExpirationPolicy;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.model.LinkStats;
import com.javaded78.urlshorter.domain.model.ResolvedLink;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import java.time.Clock;
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
 * <p>All time is sourced from an injected {@link Clock} ({@code clock.instant()}), never {@code
 * Instant.now()} directly, so the {@code expiresAt} re-checks in {@code resolve} are deterministic
 * in tests.
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
   * Resolves a code for redirection (cache-aside lookup, ADR-002) and times the end-to-end redirect
   * (controller → service) behind {@code MetricsRecorder#redirectDuration}.
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
    return repository.findByCodeIgnoreCase(code).orElseThrow(() -> notFound(code));
  }

  /**
   * Click statistics for a code (ADR-003): persisted DB count plus the pending Redis counter — the
   * logically current value, without waiting for the flush interval. Lookup is case-insensitive
   * like redirects, because counters are keyed by the stored (lowercase) code.
   *
   * @throws LinkNotFoundException if no such link exists
   */
  public LinkStats getStats(String code) {
    Link link = repository.findByCodeIgnoreCase(code).orElseThrow(() -> notFound(code));
    long pending = clickCounter.get(link.code());
    return new LinkStats(link.code(), link.clickCount() + pending);
  }

  /**
   * Resolves a code for redirection (cache-aside lookup, ADR-002):
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
    // Singleflight + negative caching: the cache coalesces concurrent cold
    // misses for this code into one PostgreSQL lookup, and short-circuits Bloom-absent and recently
    // absent codes before any DB hit. The miss supplier below performs the actual lookup and, on
    // absence, records the short-TTL negative marker via the cache.
    Optional<CachedLink> resolved =
        metrics.timeRedisLookup(() -> linkCache.coalesce(code, () -> lookupByCode(code)));
    if (resolved.isPresent()) {
      CachedLink link = resolved.get();
      if (ExpirationPolicy.isExpired(link.expiresAt(), clock.instant())) {
        metrics.linkExpired();
        throw new LinkExpiredException(code);
      }
      // Click counts once per redirect — whether the target came from a cache hit or a cold
      // PostgreSQL lookup (the double-count was the bug this fixes). The coalesce supplier must
      // NOT count too, hence lookupByCode never increments.
      recordIncrement(code);
      metrics.linkRedirected();
      return new ResolvedLink(link.code(), link.originalUrl());
    }
    // Absent: the negative cache marker has been written inside coalesce — do not re-lookup.
    metrics.linkNotFound();
    throw new LinkNotFoundException(code);
  }

  /** PostgreSQL lookup used as the {@link LinkCache#coalesce} miss supplier. */
  private Optional<CachedLink> lookupByCode(String code) {
    return metrics.timePostgresLookup(
        () ->
            repository
                .findByCodeIgnoreCase(code)
                .map(
                    link -> {
                      if (ExpirationPolicy.isExpired(link.expiresAt(), clock.instant())) {
                        // Expired links are never cached (ADR-006) and never counted.
                        metrics.linkExpired();
                        throw new LinkExpiredException(code);
                      }
                      metrics.linkRedirected();
                      return new CachedLink(link.code(), link.originalUrl(), link.expiresAt());
                    }));
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
}
