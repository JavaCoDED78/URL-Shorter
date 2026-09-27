package com.javaded78.urlshorter.infra.redis.script;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Atomic counter drain (Task 8.3): reads the value and deletes the key in a
 * single Lua execution, so increments cannot interleave between GET and DEL.
 * A whole Lua script runs atomically in Redis. Idempotent for missing keys:
 * returns {@code nil}, the caller treats it as "nothing to flush".
 *
 * <p>Requires a plain string counter ({@code INCR} model from Task 7.1).
 */
@Component
public class CounterDrainScript {

    private static final String LUA = """
            local value = redis.call('GET', KEYS[1])
            if value == false then
                return nil
            end
            redis.call('DEL', KEYS[1])
            return tonumber(value)
            """;

    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA, Long.class);

    public DefaultRedisScript<Long> get() {
        return script;
    }
}
