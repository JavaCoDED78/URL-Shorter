package com.javaded78.urlshorter.infra.redis.script;

import java.util.List;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Atomic counter drain (Tasks 8.3, 23.x): reads every counter and deletes its key in a single Lua
 * execution, so increments cannot interleave between the read and the delete. One Lua script now
 * processes a whole batch of {@code clicks:*} keys at once, so the drain is O(number of batches)
 * round-trips, not O(number of counters) — it stops scaling with the number of hot links.
 *
 * <p>Idempotent for missing keys: a drained or absent key does not appear in the reply, so re-running
 * is safe. Each drained counter comes back as a [key, value] pair (raw strings); the caller pairs
 * them and keeps only value > 0. Requires plain string counters (INCR model from Task 7.1).
 */
@Component
public class CounterDrainScript {

  private static final String BATCH_LUA =
      """
              local out = {}
              for i = 1, #KEYS do
                local v = redis.call('GET', KEYS[i])
                if v ~= false then
                  redis.call('DEL', KEYS[i])
                  out[#out + 1] = KEYS[i]
                  out[#out + 1] = v
                end
              end
              return out
              """;

  private final DefaultRedisScript<List<String>> batch = newBatchScript();

  @SuppressWarnings("unchecked")
  private static DefaultRedisScript<List<String>> newBatchScript() {
    // List.class sets the runtime result class to List, so RedisTemplate passes the Lua array
    // through unchanged; the double cast is the standard idiom for a raw Class with a parameterized
    // type (no unchecked warning at the call site).
    return (DefaultRedisScript<List<String>>) (RedisScript<?>) new DefaultRedisScript<>(BATCH_LUA, List.class);
  }

  /** One atomic read-and-reset over a whole batch of {@code clicks:*} keys. */
  public DefaultRedisScript<List<String>> batch() {
    return batch;
  }
}
