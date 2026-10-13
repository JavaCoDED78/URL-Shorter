package com.javaded78.urlshorter.infra.redis.cache;

import com.javaded78.urlshorter.config.ShortenerProperties;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Short-TTL negative cache for the redirect path (Task 21.2).
 *
 * <p>Records that a code was looked up and <em>did not exist<em> (a created-then-deleted link, or a
 * link that expired) under a {@code neg:{code}} key with a short TTL, so a repeated miss of the
 * same code — the common abuse pattern for a URL-shortener — short-circuits against Redis instead
 * of hammering PostgreSQL on every request.
 *
 * <p>This is the cross-replica layer: the in-memory {@link CodeBloomFilter} already shields
 * <em>distinct<em> random codes (never created anywhere), while this marker shields
 * <em>repeated<em> misses of the <em>same<em> code.
 *
 * <p>Every Redis read/write degrades to a miss or no-op on a {@code DataAccessException}, never
 * letting the exception escape — a dead Redis just means the redirect falls through to PostgreSQL,
 * which remains the source of truth.
 */
@Component
public class RedisNegativeCache {

  private static final Logger log = LoggerFactory.getLogger(RedisNegativeCache.class);

  static final String KEY_PREFIX = "neg:";

  /**
   * Marker value written under {@code neg:{code}} to signal "this code is known absent". A plain
   * string chosen so it can never collide with a real cached link value.
   */
  static final String MARKER = "absent";

  private final StringRedisTemplate redis;
  private final Duration negativeTtl;

  public RedisNegativeCache(
      StringRedisTemplate redis, ShortenerProperties properties, Clock clock) {
    this.redis = redis;
    this.negativeTtl = properties.cache().negativeTtl();
  }

  /**
   * Whether {@code code} is currently marked absent (Task 21.2). A {@code false} answer — whether
   * the key is genuinely absent or Redis was unavailable — means "not known absent", so the caller
   * falls through to a real lookup; a dead Redis must never masquerade as a known-absent code.
   *
   * @param code the (case-insensitive) link code
   * @return {@code true} only when a negative marker is present
   */
  public boolean isAbsent(String code) {
    final String key = key(code);
    try {
      return MARKER.equals(redis.opsForValue().get(key));
    } catch (DataAccessException ex) {
      log.warn("negative cache presence check failed for {}: {} — treating as present", key, ex);
      return false;
    }
  }

  /**
   * Records that {@code code} was looked up and is absent, written with the configured negative TTL
   * so it expires well before any plausible create race window.
   *
   * @param code the (case-insensitive) link code
   */
  public void markAbsent(String code) {
    final String key = key(code);
    try {
      redis.opsForValue().set(key, MARKER, negativeTtl);
    } catch (DataAccessException ex) {
      log.warn("negative cache write failed for {}: {}", key, ex.getMessage());
    }
  }

  /**
   * Records that {@code code} now exists: evicts any stale negative marker so a freshly created
   * link resolves against PostgreSQL instead of being short-circuited to a 404.
   *
   * @param code the (case-insensitive) link code
   */
  public void evict(String code) {
    try {
      redis.delete(key(code));
    } catch (DataAccessException ex) {
      log.warn("negative cache evict failed for {} — {}", key(code), ex.getMessage());
    }
  }

  static String key(String code) {
    return KEY_PREFIX + code.toLowerCase(java.util.Locale.ROOT);
  }
}
