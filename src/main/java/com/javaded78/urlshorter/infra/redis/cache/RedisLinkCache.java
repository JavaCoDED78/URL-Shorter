package com.javaded78.urlshorter.infra.redis.cache;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.cache.CachedLink;
import com.javaded78.urlshorter.domain.cache.LinkCache;
import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link LinkCache} (Tasks 6.1-6.4). Values are JSON; keys are {@code
 * link:{lowercaseCode}} so lookups stay consistent with the case-insensitive {@code lower(code)}
 * unique index.
 *
 * <p>Uses a dedicated {@link ObjectMapper} rather than the web one: cache serialization must not
 * depend on HTTP message-converter configuration.
 *
 * <p>Stampede protection (Task 21.1): {@link #coalesce} coalesces concurrent misses for the same
 * code behind a single in-flight PostgreSQL lookup, so an evicted/expired hot key can no longer fan
 * {@code N} concurrent reads into one.
 *
 * <p>Negative caching (Task 21.2): {@link #coalesce} short-circuits the database for codes the
 * Bloom filter has declared absent and for codes with a recent negative marker, so a scan of
 * nonexistent codes never reaches PostgreSQL.
 *
 * <p>Redis unavailability degrades the cache to a pass-through (Task 17.1): reads miss, writes are
 * skipped, evictions are skipped — every access then flows through PostgreSQL, which remains the
 * source of truth.
 */
@Component
public class RedisLinkCache implements LinkCache {

  private static final Logger log = LoggerFactory.getLogger(RedisLinkCache.class);

  static final String KEY_PREFIX = "link:";

  /**
   * In-flight misses per code: while a {@code miss} supplier is running for a code, its slot holds
   * the {@link CompletableFuture} that every concurrent caller joins, coalescing them into one run.
   * Removed once resolved so the next miss for the same code starts a fresh lookup.
   */
  private final ConcurrentHashMap<String, CompletableFuture<Optional<CachedLink>>> inFlight =
      new ConcurrentHashMap<>();

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Duration configuredTtl;
  private final CacheTtlPolicy ttlPolicy;
  private final Clock clock;
  private final CodeBloomFilter bloom;
  private final RedisNegativeCache negativeCache;
  private final MetricsRecorder metrics;

  @Autowired
  public RedisLinkCache(
      StringRedisTemplate redis,
      ShortenerProperties properties,
      Clock clock,
      CodeBloomFilter bloom,
      RedisNegativeCache negativeCache,
      MetricsRecorder metrics) {
    this(redis, cacheObjectMapper(), properties, clock, bloom, negativeCache, metrics);
  }

  /** Package-private for tests that inject a pre-configured mapper and a fixed clock. */
  RedisLinkCache(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      ShortenerProperties properties,
      Clock clock,
      CodeBloomFilter bloom,
      RedisNegativeCache negativeCache,
      MetricsRecorder metrics) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.configuredTtl = properties.cache().ttl();
    this.ttlPolicy = new CacheTtlPolicy(properties.cache().jitter());
    this.clock = clock;
    this.bloom = bloom;
    this.negativeCache = negativeCache;
    this.metrics = metrics;
  }

  // com.fasterxml.jackson.databind ObjectMapper is the 2.x compat layer the project uses;
  // every set*Inclusion overload is @Deprecated with no non-deprecated equivalent, so the
  // call below is intentional. Silence the warning here.
  @SuppressWarnings("deprecation")
  private static ObjectMapper cacheObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
    return mapper;
  }

  @Override
  public Optional<CachedLink> get(String code) {
    final String key = key(code);
    final String json;
    try {
      json = redis.opsForValue().get(key);
    } catch (DataAccessException ex) {
      // Redis down: degrade to a miss so the caller falls back to
      // PostgreSQL (Task 17.1).
      log.warn("redis unavailable on cache read for {}: {}", key, ex);
      return Optional.empty();
    }
    if (json == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(json, CachedLink.class));
    } catch (JsonProcessingException | RuntimeException ex) {
      // Corrupted entry: evict and fall back to the database rather
      // than failing the redirect (Task 6.5).
      log.warn("evicting unreadable cache entry {}: {}", key, ex);
      evict(code);
      return Optional.empty();
    }
  }

  @Override
  public Optional<CachedLink> coalesce(String code, Supplier<Optional<CachedLink>> miss) {
    // Classic cache-aside first: if the Redis positive cache already has this link, return it and
    // report a cache hit. This is checked before the Bloom filter so the hit/miss contract (see
    // docs/observability-contract) holds for the real-link path.
    Optional<CachedLink> cached = get(code);
    if (cached.isPresent()) {
      metrics.cacheHit();
      return cached;
    }
    metrics.cacheMiss();

    // Bloom absent: the code is definitely not present, so skip PostgreSQL entirely (Task 21.2).
    // A fresh random code was never created anywhere and is absent from every replica's filter —
    // the cheap shield for a code scan.
    if (!bloom.contains(code)) {
      metrics.cacheNegativeHit();
      return Optional.empty();
    }
    // Bloom present but a short-TTL negative marker is recorded: the code was created and later
    // deleted (or expired), so a Bloom-present lookup would still miss — short-circuit against
    // Redis instead of hitting PostgreSQL again (Task 21.2).
    if (negativeCache.isAbsent(code)) {
      metrics.cacheNegativeHit();
      return Optional.empty();
    }

    // Bloom present and not-negative: coalesce concurrent misses for this code into a single
    // in-flight lookup (Task 21.1).
    //
    // We use putIfAbsent, not computeIfAbsent. The Javadoc for computeIfAbsent explicitly allows
    // the mapping function to run more than once under contention — with an owner flag set inside
    // that mapping, several threads can all become the "owner" and each perform a real lookup,
    // which is exactly the stampede we are trying to prevent. putIfAbsent stores atomically and
    // only when the slot is empty, so we retry until we win the race: whoever installs the future
    // is the sole owner, everyone else joins their future.
    CompletableFuture<Optional<CachedLink>> inFlight = null;
    boolean owner = false;
    do {
      CompletableFuture<Optional<CachedLink>> existing = this.inFlight.get(code);
      if (existing != null) {
        inFlight = existing;
        break;
      }
      // The slot is empty: either nobody is looking this code up yet, or an owner just completed
      // and cleared the marker. Re-read the positive cache before trying to become the next owner.
      // An owner writes the result to Redis *before* completing the shared future and removing the
      // slot, so a caller arriving after that remove finds the value here instead of spawning a
      // second DB lookup — closing the remove→put window that caused the stampede.
      Optional<CachedLink> warm = get(code);
      if (warm.isPresent()) {
        return warm;
      }
      // Install a bare (not-yet-run) future. putIfAbsent returns the previous value: null means we
      // won the race and own the lookup; a non-null return means another caller installed the
      // future and we loop back to join it.
      CompletableFuture<Optional<CachedLink>> fresh = new CompletableFuture<>();
      existing = this.inFlight.putIfAbsent(code, fresh);
      if (existing == null) {
        inFlight = fresh;
        owner = true;
      }
    } while (!owner);

    if (owner) {
      try {
        // Sole owner: run the miss supplier exactly once and complete the shared future with it.
        Optional<CachedLink> resolved = miss.get();
        if (resolved.isPresent()) {
          // Populate the positive cache BEFORE releasing the shared future: a concurrent caller
          // that starts its lookup after we finish must find a cache hit rather than re-entering
          // PostgreSQL (this closes the remove→put window that caused the stampede).
          put(resolved.get());
        } else {
          // Absent: record the short-TTL negative marker so repeated misses of the same code
          // short-circuit against Redis until the marker expires (Task 21.2).
          negativeCache.markAbsent(code);
          metrics.cacheNegativeHit();
        }
        // Complete the shared future before removing the in-flight slot so joiners proceed and a
        // late caller finds the just-populated cache hit instead of re-entering the lookup.
        inFlight.complete(resolved);
        this.inFlight.remove(code, inFlight);
        return resolved;
      } catch (RuntimeException ex) {
        this.inFlight.remove(code, inFlight);
        inFlight.completeExceptionally(ex);
        throw ex;
      }
    }

    // Another caller owns this code's lookup; share its resolved result (Task 21.1).
    try {
      return inFlight.join();
    } catch (CompletionException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new IllegalStateException(cause);
    }
  }

  @Override
  public void put(CachedLink link) {
    ttlPolicy
        .ttlFor(link.expiresAt(), clock.instant(), configuredTtl)
        .ifPresent(
            ttl -> {
              try {
                redis
                    .opsForValue()
                    .set(key(link.code()), objectMapper.writeValueAsString(link), ttl);
              } catch (JsonProcessingException ex) {
                throw new IllegalStateException(
                    "failed to serialize cached link " + link.code(), ex);
              } catch (DataAccessException ex) {
                // Redis down: the redirect itself is unaffected —
                // PostgreSQL serves the reads (Task 17.1).
                log.warn("redis unavailable on cache write for {}: {}", key(link.code()), ex);
              }
            });
  }

  @Override
  public void evict(String code) {
    try {
      redis.delete(key(code));
    } catch (DataAccessException ex) {
      log.warn("redis unavailable on cache evict for {}: {}", key(code), ex);
    }
    // Also drop the short-TTL negative marker so a deleted code does not keep short-circuiting.
    negativeCache.evict(code);
  }

  @Override
  public void markExists(String code) {
    // Record existence so the Bloom filter no longer short-circuits a redirect for this code, and
    // drop any stale negative marker so the freshly created link resolves against PostgreSQL
    // instead of a 404.
    bloom.markExists(code);
    negativeCache.evict(code);
  }

  public static String key(String code) {
    return KEY_PREFIX + code.toLowerCase(Locale.ROOT);
  }
}
