package com.javaded78.urlshorter.infra.redis.idempotency;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.idempotency.IdempotencyStore;
import com.javaded78.urlshorter.domain.model.Link;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link IdempotencyStore} for the create endpoint.
 *
 * <p>A stored result lives under {@code idem:{sha256(key)}} with a ~24h TTL. Two Redis states
 * matter between the atomic claim and the stored result:
 *
 * <ul>
 *   <li>{@link #PENDING} — the key is claimed by an in-flight request (a bare sentinel written with
 *       {@code SET NX});
 *   <li>a JSON-serialized {@link Link} — the request finished and its result is replayable.
 * </ul>
 *
 * <p>The {@link #CLAIM_OR_DETECT} Lua script runs {@code GET} plus the {@code SET NX} claim in one
 * atomic execution, so there is no window in which two concurrent callers both see an absent key
 * and both create — exactly one wins the claim, the other gets a replay or waits for the winner.
 *
 * <p>Redis unavailability degrades to a plain, non-idempotent create (availability-wins, like the
 * cache-aside path); {@link #storeCompleted} also degrades open so a successful create whose result
 * could not be recorded never surfaces a spurious failure to the caller.
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

  private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);

  static final String KEY_PREFIX = "idem:";

  /**
   * Sentinel written by the claimer. A plain string that can never collide with a JSON-serialized
   * {@link Link} value, so a {@code GET} can tell "in-flight" from "ready to replay".
   */
  static final String PENDING = "IDEMPOTENCY_PENDING";

  private static final long CLAIM_OR_DETECT = 0; // key was absent -> we claimed it
  private static final long KEY_EXISTS = 1; // key holds PENDING (owned by an in-flight request)
  private static final long HAS_RESULT = 2; // key holds a replayable result

  private static final String LUA =
      """
          -- Atomically claim the key with SET NX, marking it in-flight with the PENDING
          -- sentinel. A truthy reply means we claimed an absent key (return 0); a falsy
          -- reply means the key already existed, so we classify it below. We check truthiness
          -- rather than the reply string on purpose: some Redis/RESP builds return a table
          -- instead of the literal "OK", so an equality check would never claim the key.
          if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', tonumber(ARGV[2])) then
              return 0
          end
          local v = redis.call('GET', KEYS[1])
          if v == ARGV[1] then
              return 1
          end
          return 2
          """;

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Duration ttl;
  private final Duration waitTimeout;
  private final Clock clock;
  private final DefaultRedisScript<Long> claimOrDetect;

  public RedisIdempotencyStore(
      StringRedisTemplate redis, ShortenerProperties properties, Clock clock) {
    this.redis = redis;
    this.objectMapper = storeObjectMapper();
    this.ttl = properties.idempotency().ttl();
    this.waitTimeout = properties.idempotency().waitTimeout();
    this.clock = clock;
    this.claimOrDetect = new DefaultRedisScript<>(LUA, Long.class);
  }

  @Override
  public Link execute(String idempotencyKey, Supplier<Link> create) {
    final String storeKey = key(idempotencyKey);
    final long ttlMs = ttl.toMillis();

    Long outcome;
    try {
      outcome =
          redis.execute(
              claimOrDetect, Collections.singletonList(storeKey), PENDING, Long.toString(ttlMs));
    } catch (DataAccessException ex) {
      // Redis unavailable: degrade to a plain create (no dedup), matching the cache-aside path.
      log.warn("idempotency store unavailable for {}: {}", storeKey, ex);
      return create.get();
    }

    if (outcome == null) {
      return create.get();
    }
    if (outcome == HAS_RESULT) {
      return replay(storeKey);
    }
    if (outcome == KEY_EXISTS) {
      return waitForCompletion(storeKey, ttlMs, create);
    }
    // outcome == CLAIM_OR_DETECT: we own the key — create once and record the success.
    try {
      Link link = create.get();
      storeCompleted(storeKey, link, ttlMs);
      return link;
    } catch (RuntimeException ex) {
      // A failed create (invalid URL, collision exhaustion, ...) must not poison the key: release
      // the claim so a later retry with the same key can succeed.
      release(storeKey);
      throw ex;
    }
  }

  /**
   * Another caller owns this key and has not finished. Poll until the key carries a replayable
   * result, take over a key freed by a failed create, or give up with a timeout — whichever comes
   * first, bounded by {@code waitTimeout}. Never creates twice for the same key.
   */
  private Link waitForCompletion(String storeKey, long ttlMs, Supplier<Link> create) {
    Instant deadline = clock.instant().plus(waitTimeout);
    while (Instant.now().isBefore(deadline)) {
      String raw = readRaw(storeKey);
      if (raw != null) {
        if (!PENDING.equals(raw)) {
          return decode(storeKey, raw);
        }
        // Still owned by an in-flight request: wait a beat and re-check.
        sleepInterruptibly(pollIntervalMillis());
        continue;
      }
      // Key gone (the owner's create failed and released it, or its TTL expired): take it over and
      // create exactly once.
      if (claimFresh(storeKey, ttlMs)) {
        return runAndStore(storeKey, ttlMs, create);
      }
    }
    throw new IllegalStateException(
        "idempotency key still in flight after " + waitTimeout + "; retry later with the same key");
  }

  private Link runAndStore(String storeKey, long ttlMs, Supplier<Link> create) {
    try {
      Link link = create.get();
      storeCompleted(storeKey, link, ttlMs);
      return link;
    } catch (RuntimeException ex) {
      release(storeKey);
      throw ex;
    }
  }

  private Link replay(String storeKey) {
    String raw = readRaw(storeKey);
    if (raw == null) {
      // The result expired between the script and this read: create a fresh link for us.
      if (claimFresh(storeKey, ttlToMillis())) {
        throw new IllegalStateException("idempotency result expired mid-request; retry");
      }
      throw new IllegalStateException("idempotency result vanished mid-request; retry");
    }
    return decode(storeKey, raw);
  }

  private Link decode(String storeKey, String raw) {
    try {
      return objectMapper.readValue(raw, Link.class);
    } catch (JsonProcessingException ex) {
      // A corrupted stored result cannot be replayed; fail loudly rather than serve garbage.
      log.warn("unreadable idempotency entry {}: {}", storeKey, ex);
      throw new IllegalStateException("corrupted idempotency result for " + storeKey, ex);
    }
  }

  private boolean claimFresh(String storeKey, long ttlMs) {
    // Re-run the atomic claim: returns CLAIM_OR_DETECT (0) only when we actually took the key
    // away from an absent/freed slot, so a racing takeover still creates exactly once.
    Long outcome;
    try {
      outcome =
          redis.execute(
              claimOrDetect, Collections.singletonList(storeKey), PENDING, Long.toString(ttlMs));
    } catch (DataAccessException ex) {
      return false;
    }
    return outcome == CLAIM_OR_DETECT;
  }

  private void storeCompleted(String storeKey, Link link, long ttlMs) {
    try {
      String json = objectMapper.writeValueAsString(link);
      redis.opsForValue().set(storeKey, json, ttl);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("failed to serialize idempotency result", ex);
    } catch (DataAccessException ex) {
      // Best-effort: the link was already persisted, so a failed record must not fail the create.
      log.warn("failed to record idempotency result for {}: {}", storeKey, ex);
    }
  }

  private void release(String storeKey) {
    try {
      redis.delete(storeKey);
    } catch (DataAccessException ex) {
      log.warn("failed to release idempotency key {}: {}", storeKey, ex);
    }
  }

  private String readRaw(String storeKey) {
    try {
      return redis.opsForValue().get(storeKey);
    } catch (DataAccessException ex) {
      return null;
    }
  }

  private void sleepInterruptibly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for idempotency key", ex);
    }
  }

  private long ttlToMillis() {
    return ttl.toMillis();
  }

  private long pollIntervalMillis() {
    return 20;
  }

  private static String key(String idempotencyKey) {
    // Hash the client-supplied key so the stored key is bounded and independent of its content
    // (a raw key could otherwise carry Redis metacharacters or leak into the key space).
    return KEY_PREFIX + sha256Hex(idempotencyKey);
  }

  private static String sha256Hex(String input) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  // Every set*Inclusion overload on com.fasterxml.jackson.databind.ObjectMapper (the 2.x
  // compat layer the project uses) is @Deprecated with no non-deprecated equivalent; the
  // behaviour is identical to RedisLinkCache.cacheObjectMapper(). Silence the warning here.
  @SuppressWarnings("deprecation")
  private static ObjectMapper storeObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
    return mapper;
  }
}
