package com.javaded78.urlshorter.domain.service;

import com.javaded78.urlshorter.domain.cache.LinkCache;
import com.javaded78.urlshorter.domain.config.WriteServiceConfig;
import com.javaded78.urlshorter.domain.counter.ClickCounter;
import com.javaded78.urlshorter.domain.exception.AliasAlreadyExistsException;
import com.javaded78.urlshorter.domain.exception.DuplicateCodeException;
import com.javaded78.urlshorter.domain.exception.LinkNotFoundException;
import com.javaded78.urlshorter.domain.exception.RateLimitExceededException;
import com.javaded78.urlshorter.domain.idempotency.IdempotencyStore;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.domain.validation.AliasValidator;
import com.javaded78.urlshorter.domain.validation.UrlValidator;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * <p>Idempotency (Idempotency-Key): when {@code idempotencyKey} is present, the whole create is run
 * inside {@link IdempotencyStore#execute} — a retried request replays the original result instead
 * of creating a second link. When it is absent the create runs directly.
 *
 * <p>Throws {@link IllegalArgumentException} (400 on invalid URL/alias/expiration), {@link
 * AliasAlreadyExistsException} (409), {@link RateLimitExceededException} (429) and {@link
 * IllegalStateException} (generated codes keep colliding, or a concurrent idempotent request times
 * out waiting for its winner).
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
  private final IdempotencyStore idempotencyStore;

  public WriteLinkService(
      Clock clock,
      LinkRepository repository,
      CodeGenerator codeGenerator,
      UrlValidator urlValidator,
      AliasValidator aliasValidator,
      WriteServiceConfig config,
      LinkCache linkCache,
      ClickCounter clickCounter,
      MetricsRecorder metrics,
      RateLimiter rateLimiter,
      CreateRateLimitSource rateLimitSource,
      IdempotencyStore idempotencyStore) {
    this.clock = clock;
    this.repository = repository;
    this.codeGenerator = codeGenerator;
    this.urlValidator = urlValidator;
    this.aliasValidator = aliasValidator;
    this.maxCollisionRetries = config.maxCollisionRetries();
    this.linkCache = linkCache;
    this.clickCounter = clickCounter;
    this.metrics = metrics;
    this.rateLimiter = rateLimiter;
    this.rateLimitSource = rateLimitSource;
    this.idempotencyStore = idempotencyStore;
  }

  /**
   * Creates a new short link.
   *
   * @param originalUrl destination URL, validated
   * @param alias optional custom alias; {@code null} or blank requests a generated code
   * @param expiresAt optional expiration instant (UTC); {@code null} means the link never expires
   * @param idempotencyKey optional client-supplied {@code Idempotency-Key}; when present the create
   *     is made idempotent (a retry with the same key replays the original result), when absent or
   *     blank the create runs directly
   * @return the created link
   * @throws IllegalArgumentException on invalid URL or alias
   * @throws AliasAlreadyExistsException if the code is already taken
   * @throws RateLimitExceededException if the create-endpoint rate limit is exceeded (429)
   * @throws IllegalStateException if generated codes keep colliding, or a concurrent idempotent
   *     request times out waiting for its winner
   */
  public Link create(
      String originalUrl,
      @Nullable String alias,
      @Nullable Instant expiresAt,
      @Nullable String idempotencyKey) {
    // Idempotent path: a retried request carrying the same key replays the original result instead
    // of creating a second link. The whole create (rate-limit probe, validation, insert) runs
    // inside
    // the store, so a replay consumes no rate-limit budget and no insert happens.
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      return idempotencyStore.execute(
          idempotencyKey, () -> doCreate(originalUrl, alias, expiresAt));
    }
    return doCreate(originalUrl, alias, expiresAt);
  }

  /**
   * The core create: enforce the rate limit, validate, and insert. This is what {@link
   * IdempotencyStore#execute} wraps for the idempotent path — so it runs at most once per key, and
   * a replay never re-runs it.
   *
   * <p>Enforce the create-endpoint rate limit before any other work. A denied probe returns
   * immediately as 429 and never consumes a DB/Redis round-trip. The limiter is scoped per client:
   * the key is the caller's IP (via rateLimitSource), so an abuser can only ever hit their own
   * ceiling — a single global counter would let one client block everyone. The probe itself must
   * not be rate limited.
   *
   * <p>URL/alias validation runs OUTSIDE any transaction on purpose: the URL validation does a
   * blocking DNS lookup, and holding a pooled PostgreSQL connection during that lookup is exactly
   * what starved the connection pool under the 300-way ConcurrencyIT (only 10 creates survived
   * before the 5s connection-timeout kicked in). Validate first, then insert via {@link
   * #createWithGeneratedCode}/{@link #createWithAlias}, which never hold a transaction.
   */
  private Link doCreate(String originalUrl, @Nullable String alias, @Nullable Instant expiresAt) {
    if (!rateLimiter.tryAcquire(new RateLimitScope("create", rateLimitSource.resolveClientKey()))) {
      throw new RateLimitExceededException();
    }
    urlValidator.validate(originalUrl);
    if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
      throw new IllegalArgumentException("expiresAt must be in the future, got: " + expiresAt);
    }
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
   * Deletes a link. Order of operations:
   *
   * <pre>
   * evict cache → discard pending counter → delete DB row → evict cache again (after commit)
   * </pre>
   *
   * <p>Evicting BEFORE the DB delete is the safe failure mode: if the delete fails, the row is
   * still alive and the cache repopulates on the next read (self-healing) — a stale cached link is
   * never served after a successful delete.
   *
   * <p>The second evict runs <b>after the transaction commits</b> — via a {@link
   * TransactionSynchronization} {@code afterCommit} hook — not inside the transaction body. Until
   * the commit the row is still visible to concurrent readers, so a redirect racing the delete can
   * read the still-committed row and re-populate the cache between the first evict and this commit;
   * a second evict issued inside the transaction would fire <i>before</i> that re-population and
   * let the stale entry survive. Evicting in {@code afterCommit} orders the eviction after the
   * deletion becomes globally visible, closing that window (see {@link
   * ConcurrencyIT#deleteVersusRedirectRaceConverges}).
   *
   * <p>Counter policy: discard — pending clicks belong to the link row; once the row is
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
    // Second evict AFTER commit: any concurrent redirect that read the still-committed row
    // (between the pre-delete evict and this commit) and re-populated the cache is evicted once
    // the deletion is globally visible — so a stale entry can never outlive the row.
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            linkCache.evict(link.code());
          }
        });
  }

  private Link createWithAlias(
      String originalUrl, @Nullable String alias, @Nullable Instant expiresAt) {
    String code = aliasValidator.validateAndNormalize(alias);
    if (repository.existsByCode(code)) {
      throw new AliasAlreadyExistsException(code);
    }
    try {
      Link link = Link.created(code, originalUrl, expiresAt, clock.instant());
      repository.save(link);
      return link;
    } catch (DuplicateCodeException ex) {
      // Lost the race between existsByCode and insert — the database
      // unique index is the final authority.
      throw new AliasAlreadyExistsException(code);
    }
  }

  private Link createWithGeneratedCode(String originalUrl, @Nullable Instant expiresAt) {
    for (int attempt = 1; attempt <= maxCollisionRetries; attempt++) {
      String code = codeGenerator.generate();
      try {
        Link link = Link.created(code, originalUrl, expiresAt, clock.instant());
        repository.save(link);
        return link;
      } catch (DuplicateCodeException ex) {
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
