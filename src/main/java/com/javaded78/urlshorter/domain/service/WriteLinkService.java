package com.javaded78.urlshorter.domain.service;

import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.cache.LinkCache;
import com.javaded78.urlshorter.domain.counter.ClickCounter;
import com.javaded78.urlshorter.domain.exception.AliasAlreadyExistsException;
import com.javaded78.urlshorter.domain.exception.LinkNotFoundException;
import com.javaded78.urlshorter.domain.exception.RateLimitExceededException;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.domain.validation.AliasValidator;
import com.javaded78.urlshorter.domain.validation.UrlValidator;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write path for short links (CQRS-lite split from {@link ReadLinkService}).
 *
 * <p>Owns create and delete — the request-side concerns: URL/alias validation, rate limiting,
 * collision retries, transactional inserts and the cache-evict dance around a delete. Deliberately
 * does NOT know about redirects, cache-aside lookups, click counting or read stats — those live in
 * {@link ReadLinkService}. Keeping the write and read responsibilities in separate beans means the
 * hot redirect path never pays for a rate-limit probe, and each path can evolve (and be tested) on
 * its own.
 *
 * <p>All time is sourced from an injected {@link Clock} ({@code clock.instant()}), never {@code
 * Instant.now()} directly, so {@code create} and its {@code expiresAt} validation are deterministic
 * in tests.
 *
 * <p>Create algorithm:
 *
 * <ul>
 *   <li>validate the URL and, when present, the alias (normalized to lowercase);
 *   <li>with an alias: pre-check {@code existsByCode}, insert, and translate a constraint violation
 *       into {@link AliasAlreadyExistsException} — the pre-check is an optimization, the database
 *       unique index is the concurrency guarantee;
 *   <li>without an alias: generate a Base62 code, insert, and retry a bounded number of times on
 *       collision.
 * </ul>
 *
 * <p>Inserts use {@code saveAndFlush} so the unique-constraint outcome is known inside the retry
 * loop, not at an unpredictable commit point.
 *
 * <p>Throws {@link IllegalArgumentException} (400 on invalid URL/alias/expiration), {@link
 * AliasAlreadyExistsException} (409), {@link RateLimitExceededException} (429) and {@link
 * IllegalStateException} (generated codes keep colliding).
 */
@Service
public class WriteLinkService {

  private static final Logger log = LoggerFactory.getLogger(WriteLinkService.class);

  private final Clock clock;
  private final LinkRepository repository;
  private final CodeGenerator codeGenerator;
  private final UrlValidator urlValidator;
  private final AliasValidator aliasValidator;
  private final int maxCollisionRetries;
  private final LinkCache linkCache;
  private final ClickCounter clickCounter;
  private final MetricsRecorder metrics;
  private final RateLimiter rateLimiter;
  private final CreateRateLimitSource rateLimitSource;

  public WriteLinkService(
      Clock clock,
      LinkRepository repository,
      CodeGenerator codeGenerator,
      UrlValidator urlValidator,
      AliasValidator aliasValidator,
      ShortenerProperties properties,
      LinkCache linkCache,
      ClickCounter clickCounter,
      MetricsRecorder metrics,
      RateLimiter rateLimiter,
      CreateRateLimitSource rateLimitSource) {
    this.clock = clock;
    this.repository = repository;
    this.codeGenerator = codeGenerator;
    this.urlValidator = urlValidator;
    this.aliasValidator = aliasValidator;
    this.maxCollisionRetries = properties.create().maxCollisionRetries();
    this.linkCache = linkCache;
    this.clickCounter = clickCounter;
    this.metrics = metrics;
    this.rateLimiter = rateLimiter;
    this.rateLimitSource = rateLimitSource;
  }

  /**
   * Creates a new short link.
   *
   * @param originalUrl destination URL, validated
   * @param alias optional custom alias; {@code null} or blank requests a generated code
   * @param expiresAt optional expiration instant (UTC); {@code null} means the link never expires
   * @return the created link
   * @throws IllegalArgumentException on invalid URL or alias
   * @throws AliasAlreadyExistsException if the code is already taken
   * @throws RateLimitExceededException if the create-endpoint rate limit is exceeded (429)
   * @throws IllegalStateException if generated codes keep colliding
   */
  public Link create(String originalUrl, String alias, Instant expiresAt) {
    // Enforce the create-endpoint rate limit before any other work. A denied
    // probe returns immediately as 429 and never consumes a DB/Redis round-trip.
    // The limiter is scoped per client: the key is the caller's IP (via
    // rateLimitSource), so an abuser can only ever hit their own ceiling — a
    // single global counter would let one client block everyone. The probe
    // itself must not be rate limited.
    if (!rateLimiter.tryAcquire(
        new RateLimitScope("create", rateLimitSource.resolveClientKey()))) {
      throw new RateLimitExceededException();
    }
    // URL/alias validation runs OUTSIDE any transaction on purpose: the URL
    // validation does a blocking DNS lookup, and holding a pooled PostgreSQL
    // connection during that lookup is exactly what starved the connection
    // pool under the 300-way ConcurrencyIT (only 10 creates survived before
    // the 5s connection-timeout kicked in). Validate first, then delegate the
    // short-lived DB insert to doCreate, which never holds a transaction.
    urlValidator.validate(originalUrl);
    if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
      throw new IllegalArgumentException("expiresAt must be in the future, got: " + expiresAt);
    }
    return doCreate(originalUrl, alias, expiresAt);
  }

  /**
   * Core of {@link #create}: inserts the link, retrying a bounded number of times on a generated-code
   * collision. No {@code @Transactional} boundary is intended here — each {@link LinkRepository#save}
   * flushes immediately, so the database unique index is enforced per attempt and a collision is
   * caught by {@link DataIntegrityViolationException} and retried (the retry depends on that
   * immediate flush, not on a transaction). {@code @Transactional} is deliberately absent, because a
   * transaction here would hold a pooled connection; the whole write path is split so that the
   * blocking DNS lookup in {@link #create} never shares a connection with the fast DB insert.
   */
  public Link doCreate(String originalUrl, String alias, Instant expiresAt) {
    if (alias == null || alias.isBlank()) {
      Link link = createWithGeneratedCode(originalUrl, expiresAt);
      metrics.linkCreated();
      linkCache.markExists(link.code());
      return link;
    }
    Link link = createWithAlias(originalUrl, alias, expiresAt);
    metrics.linkCreated();
    linkCache.markExists(link.code());
    return link;
  }

  /**
   * Deletes a link (Tasks 10.1–10.3). Order of operations (Task 10.2):
   *
   * <pre>
   * evict cache → discard pending counter → delete DB row → evict cache again
   * </pre>
   *
   * <p>Evicting BEFORE the DB delete is the safe failure mode: if the delete fails, the row is
   * still alive and the cache repopulates on the next read (self-healing) — a stale cached link is
   * never served after a successful delete. The second evict closes the narrow race where a
   * concurrent redirect re-populated the cache between the first evict and the delete.
   *
   * <p>Counter policy (Task 10.3): discard — pending clicks belong to the link row; once the row is
   * gone there is no owner for them, and flushing before delete would persist counts just to remove
   * them. An increment racing past the discard self-cleans on the next flush (the batch UPDATE
   * matches 0 rows for a deleted code).
   *
   * @throws LinkNotFoundException if no such link exists
   */
  @Transactional
  public void delete(String code) {
    Link link = repository.findByCodeIgnoreCase(code).orElseThrow(() -> notFound(code));
    linkCache.evict(link.code());
    clickCounter.delete(link.code());
    repository.deleteByCode(link.code());
    metrics.linkDeleted();
    linkCache.evict(link.code());
  }

  private Link createWithAlias(String originalUrl, String alias, Instant expiresAt) {
    String code = aliasValidator.validateAndNormalize(alias);
    if (repository.existsByCode(code)) {
      throw new AliasAlreadyExistsException(code);
    }
    try {
      Link link = Link.created(code, originalUrl, expiresAt, clock.instant());
      repository.save(link);
      return link;
    } catch (DataIntegrityViolationException ex) {
      // Lost the race between existsByCode and insert — the database
      // unique index is the final authority.
      throw new AliasAlreadyExistsException(code);
    }
  }

  private Link createWithGeneratedCode(String originalUrl, Instant expiresAt) {
    for (int attempt = 1; attempt <= maxCollisionRetries; attempt++) {
      String code = codeGenerator.generate();
      try {
        Link link = Link.created(code, originalUrl, expiresAt, clock.instant());
        repository.save(link);
        return link;
      } catch (DataIntegrityViolationException ex) {
        log.debug(
            "generated code collision on attempt {}/{}: {}", attempt, maxCollisionRetries, code);
      }
    }
    throw new IllegalStateException(
        "could not generate a unique code after " + maxCollisionRetries + " collision retries");
  }

  private LinkNotFoundException notFound(String code) {
    metrics.linkNotFound();
    return new LinkNotFoundException(code);
  }
}
