package com.javaded78.urlshorter.infra.redis.cache;

import com.javaded78.urlshorter.config.ShortenerProperties;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory per-replica Bloom membership set for short-link codes (ADR-011).
 *
 * <p>Shields PostgreSQL from a scan of random codes that were never created: a fresh random code is
 * absent from every replica's filter, so the redirect short-circuits locally to a negative result
 * without a Redis round-trip or a DB query. It is a per-process projection — not shared across
 * replicas and not persisted. The authoritative existence check remains PostgreSQL; the filter only
 * ever answers "probably exists" (which is safe — it forces a real lookup) and never "definitely
 * does not exist" (a false positive just costs one DB query).
 *
 * <p>Maintained incrementally on the write path via {@link #markExists(String)} after every
 * successful create. The filter only grows — it never forgets — which is correct by design: a code
 * that was created but later deleted still reads as "present" and is redirected to PostgreSQL,
 * where the short-TTL negative marker ({@code RedisNegativeCache}) catches the miss before the DB
 * is reached. Nothing here is shared across replicas; a fresh process simply rebuilds its own
 * filter.
 */
@Component
public class CodeBloomFilter {

  private static final Logger log = LoggerFactory.getLogger(CodeBloomFilter.class);

  private final BloomFilter filter;

  public CodeBloomFilter(ShortenerProperties properties) {
    var cache = properties.cache();
    this.filter = new BloomFilter(cache.expectedCodes(), cache.bloomFalsePositiveRate());
    log.info(
        "Bloom filter initialised: ~{} expected codes, target FPR {}, ~{} MB",
        cache.expectedCodes(),
        cache.bloomFalsePositiveRate(),
        filter.size() / (1024L * 1024L));
  }

  /**
   * Records that {@code code} exists (ADR-011). Called by the write path after a successful create
   * so the filter no longer short-circuits a redirect for it. Idempotent per key.
   *
   * @param code the (case-insensitive) link code
   */
  public void markExists(String code) {
    if (code != null && !code.isBlank()) {
      // Case-insensitive membership: the code column is unique case-insensitively (V2),
      // findByCodeIgnoreCase resolves any case on redirect, and contains() lowercases before
      // querying. markExists must normalize to the same canonical form, or the mixed-case
      // generated Base62 codes (e.g. eqAYMsH) would be wrongly treated as absent -> 404.
      filter.add(code.toLowerCase(Locale.ROOT));
    }
  }

  /**
   * Reports whether {@code code} is probably present. A {@code true} answer forces a real lookup; a
   * {@code false} answer means the code is definitely absent and the redirect can short-circuit
   * without touching PostgreSQL.
   *
   * @param code the (case-insensitive) link code
   * @return {@code true} if the filter knows the code was created (or a false positive), {@code
   *     false} if it is definitely not present
   */
  public boolean contains(String code) {
    if (code == null || code.isBlank()) {
      return false;
    }
    try {
      return filter.contains(code.toLowerCase(Locale.ROOT));
    } catch (RuntimeException ex) {
      // A corrupt filter must never hide a real link: fall through to PostgreSQL, which is the
      // source of truth (answer "present" so the caller still performs a real lookup).
      log.warn("bloom filter read failed for {}: {} — falling through to db", code, ex);
      return true;
    }
  }
}
