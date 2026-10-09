package com.javaded78.urlshorter.infra.redis.cache;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.cache.CachedLink;
import com.javaded78.urlshorter.domain.cache.LinkCache;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
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
 * <p>Redis unavailability degrades the cache to a pass-through (Task 17.1): reads miss, writes are
 * skipped, evictions are skipped — every access then flows through PostgreSQL, which remains the
 * source of truth.
 */
@Component
public class RedisLinkCache implements LinkCache {

  private static final Logger log = LoggerFactory.getLogger(RedisLinkCache.class);

  static final String KEY_PREFIX = "link:";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Duration configuredTtl;
  private final Clock clock;

  @Autowired
  public RedisLinkCache(StringRedisTemplate redis, ShortenerProperties properties, Clock clock) {
    this(redis, cacheObjectMapper(), properties, clock);
  }

  /** Package-private for tests that inject a pre-configured mapper and a fixed clock. */
  RedisLinkCache(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      ShortenerProperties properties,
      Clock clock) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.configuredTtl = properties.cache().ttl();
    this.clock = clock;
  }

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
  public void put(CachedLink link) {
    CacheTtlPolicy.ttlFor(link.expiresAt(), clock.instant(), configuredTtl)
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
  }

  static String key(String code) {
    return KEY_PREFIX + code.toLowerCase(Locale.ROOT);
  }
}
