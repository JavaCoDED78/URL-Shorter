package com.javaded78.urlshorter.domain.service;

import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.cache.CachedLink;
import com.javaded78.urlshorter.domain.cache.LinkCache;
import com.javaded78.urlshorter.domain.counter.ClickCounter;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.model.LinkStats;
import com.javaded78.urlshorter.domain.model.ResolvedLink;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.infra.persistence.LinkEntity;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Create/read application service (Task 4.4).
 *
 * <p>Create algorithm:
 *
 * <ul>
 *   <li>validate the URL (scheme/host/length) and, when present, the alias (charset/length/reserved
 *       words, normalized to lowercase);
 *   <li>with an alias: pre-check {@code existsByCode}, insert, and translate a constraint violation
 *       into {@link AliasAlreadyExistsException} — the pre-check is an optimization, the database
 *       unique index is the concurrency guarantee;
 *   <li>without an alias: generate a Base62 code, insert, and retry a bounded number of times on
 *       collision.
 * </ul>
 *
 * <p>Inserts use {@code saveAndFlush} so the unique-constraint outcome is known inside the retry
 * loop, not at an unpredictable commit point.
 */
@Service
public class LinkService {

  private static final Logger log = LoggerFactory.getLogger(LinkService.class);

  private final LinkRepository repository;
  private final CodeGenerator codeGenerator;
  private final UrlValidator urlValidator;
  private final AliasValidator aliasValidator;
  private final int maxCollisionRetries;
  private final LinkCache linkCache;
  private final ClickCounter clickCounter;
  private final MetricsRecorder metrics;

  public LinkService(
      LinkRepository repository,
      CodeGenerator codeGenerator,
      UrlValidator urlValidator,
      AliasValidator aliasValidator,
      ShortenerProperties properties,
      LinkCache linkCache,
      ClickCounter clickCounter,
      MetricsRecorder metrics) {
    this.repository = repository;
    this.codeGenerator = codeGenerator;
    this.urlValidator = urlValidator;
    this.aliasValidator = aliasValidator;
    this.maxCollisionRetries = properties.create().maxCollisionRetries();
    this.linkCache = linkCache;
    this.clickCounter = clickCounter;
    this.metrics = metrics;
  }

  /**
   * Creates a new short link.
   *
   * @param originalUrl destination URL, validated
   * @param alias optional custom alias; {@code null} or blank requests a generated code
   * @param expiresAt optional expiration instant (UTC); {@code null} means the link never expires
   * @throws IllegalArgumentException on invalid URL or alias
   * @throws AliasAlreadyExistsException if the code is already taken
   * @throws IllegalStateException if generated codes keep colliding
   */
  public Link create(String originalUrl, String alias, Instant expiresAt) {
    urlValidator.validate(originalUrl);
    if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
      throw new IllegalArgumentException("expiresAt must be in the future, got: " + expiresAt);
    }
    if (alias == null || alias.isBlank()) {
      Link link = createWithGeneratedCode(originalUrl, expiresAt);
      metrics.linkCreated();
      return link;
    }
    Link link = createWithAlias(originalUrl, alias, expiresAt);
    metrics.linkCreated();
    return link;
  }

  /**
   * Returns the link with the given code.
   *
   * @throws LinkNotFoundException if no such link exists
   */
  public Link getByCode(String code) {
    return repository.findByCode(code).map(this::toDomain).orElseThrow(() -> notFound(code));
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
  public void delete(String code) {
    Link link =
        repository.findByCodeIgnoreCase(code).map(this::toDomain).orElseThrow(() -> notFound(code));
    linkCache.evict(link.code());
    clickCounter.delete(link.code());
    repository.deleteByCode(link.code());
    metrics.linkDeleted();
    linkCache.evict(link.code());
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
    java.util.Optional<CachedLink> cached = metrics.timeRedisLookup(() -> linkCache.get(code));
    if (cached.isPresent()) {
      metrics.cacheHit();
      CachedLink hit = cached.get();
      if (hit.isExpired(Instant.now())) {
        metrics.linkExpired();
        throw new LinkExpiredException(code);
      }
      // Click counts only after the redirect target is validated (Task 7.3).
      clickCounter.increment(code);
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
    if (link.isExpired(Instant.now())) {
      // Expired links are never cached (Task 6.4) and never counted.
      metrics.linkExpired();
      throw new LinkExpiredException(code);
    }
    linkCache.put(new CachedLink(link.code(), link.originalUrl(), link.expiresAt()));
    clickCounter.increment(code);
    metrics.linkRedirected();
    return new ResolvedLink(link.code(), link.originalUrl());
  }

  private LinkNotFoundException notFound(String code) {
    metrics.linkNotFound();
    return new LinkNotFoundException(code);
  }

  private Link createWithAlias(String originalUrl, String alias, Instant expiresAt) {
    String code = aliasValidator.validateAndNormalize(alias);
    if (repository.existsByCode(code)) {
      throw new AliasAlreadyExistsException(code);
    }
    try {
      return save(code, originalUrl, expiresAt);
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
        return save(code, originalUrl, expiresAt);
      } catch (DataIntegrityViolationException ex) {
        log.debug(
            "generated code collision on attempt {}/{}: {}", attempt, maxCollisionRetries, code);
      }
    }
    throw new IllegalStateException(
        "could not generate a unique code after " + maxCollisionRetries + " collision retries");
  }

  private Link save(String code, String originalUrl, Instant expiresAt) {
    LinkEntity entity = repository.saveAndFlush(new LinkEntity(code, originalUrl, expiresAt));
    return toDomain(entity);
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
