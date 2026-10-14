package com.javaded78.urlshorter.domain.counter;

import java.util.Map;

/**
 * Click counter port (ADR-003). Every successful redirect increments {@code
 * clicks:{lowercaseCode}}; the value is a plain integer string incremented with {@code INCR} — one
 * format, no hash-based model, so the flush drains counters atomically.
 *
 * <p>Semantics: the caller increments only after the link was found, is not expired and the
 * redirect is prepared — never for 404, 410 or malformed requests.
 *
 * <p>Flush support (ADR-004): {@link #drainAll()} atomically reads and resets every counter; {@link
 * #restore(String, long)} puts a drained value back after a failed database write.
 */
public interface ClickCounter {

  /**
   * Increments the counter for the code; keys are case-insensitive.
   *
   * <p>Success is reported to the caller (which owns the {@code clicksIncremented}/ {@code
   * clicksIncrementFailed} metrics) rather than recorded here, so this infrastructure class never
   * depends on domain-level observability.
   *
   * @return {@code true} if the increment was applied to Redis, {@code false} if Redis was
   *     unavailable (best-effort — the caller counts the loss via {@code
   *     MetricsRecorder#clickIncrementFailed})
   */
  boolean increment(String code);

  /**
   * Returns the pending (not yet flushed) count for the code; keys are case-insensitive. Zero when
   * no counter exists.
   */
  long get(String code);

  /**
   * Atomically drains every counter (read-and-reset per key, so increments cannot interleave).
   * Returns {@code code -> drained value} for counters that held a positive value.
   */
  Map<String, Long> drainAll();

  /**
   * Puts a previously drained value back (recovery after a failed flush). Sums with any increments
   * that arrived in the meantime.
   */
  void restore(String code, long value);

  /** Removes the counter for the code (delete policy); idempotent. */
  void delete(String code);
}
