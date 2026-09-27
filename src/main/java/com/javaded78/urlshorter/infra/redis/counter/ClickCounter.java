package com.javaded78.urlshorter.infra.redis.counter;

/**
 * Click counter port (Task 7.1). Every successful redirect increments
 * {@code clicks:{lowercaseCode}}; the value is a plain integer string
 * incremented with {@code INCR} — one format, no hash-based model, so the
 * Sprint 8 flush can drain counters atomically.
 *
 * <p>Semantics (Task 7.3): the caller increments only after the link was
 * found, is not expired and the redirect is prepared — never for 404, 410
 * or malformed requests.
 */
public interface ClickCounter {

    /** Increments the counter for the code; keys are case-insensitive. */
    void increment(String code);
}
