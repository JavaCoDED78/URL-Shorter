package com.javaded78.urlshorter.infra.redis.counter;

import java.util.Map;

/**
 * Click counter port (Task 7.1). Every successful redirect increments
 * {@code clicks:{lowercaseCode}}; the value is a plain integer string
 * incremented with {@code INCR} — one format, no hash-based model, so the
 * flush drains counters atomically.
 *
 * <p>Semantics (Task 7.3): the caller increments only after the link was
 * found, is not expired and the redirect is prepared — never for 404, 410
 * or malformed requests.
 *
 * <p>Flush support (Task 8.3+): {@link #drainAll()} atomically reads and
 * resets every counter; {@link #restore(String, long)} puts a drained value
 * back after a failed database write.
 */
public interface ClickCounter {

    /**
     * Increments the counter for the code; keys are case-insensitive.
     */
    void increment(String code);

    /**
     * Returns the pending (not yet flushed) count for the code; keys are
     * case-insensitive. Zero when no counter exists.
     */
    long get(String code);

    /**
     * Atomically drains every counter (read-and-reset per key, so increments
     * cannot interleave). Returns {@code code -> drained value} for counters
     * that held a positive value.
     */
    Map<String, Long> drainAll();

    /**
     * Puts a previously drained value back (recovery after a failed flush).
     * Sums with any increments that arrived in the meantime.
     */
    void restore(String code, long value);
}
