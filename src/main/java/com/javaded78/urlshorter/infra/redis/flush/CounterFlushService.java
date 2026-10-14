package com.javaded78.urlshorter.infra.redis.flush;

import com.javaded78.urlshorter.domain.counter.ClickCounter;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Infra orchestrator for the click flush: drains every Redis counter, applies one batch UPDATE to
 * PostgreSQL, and restores drained values to Redis when that write fails — never losing clicks
 * silently.
 *
 * <p>Lives in {@code infra.redis.flush} alongside {@link ClickFlushJob} because it orchestrates
 * infrastructure concerns (Redis drain, DB transaction) rather than domain behaviour — the Redis
 * drain and the DB write are both behind domain ports ({@link ClickCounter}, {@link
 * LinkRepository}), so this class is an adapter-level orchestrator, not a domain service.
 *
 * <p>Recovery strategy (ADR-003 — explicitly chosen for the MVP): <b>return drained values to
 * Redis</b> ({@code INCRBY}) when the database write fails. The batch UPDATE is a single SQL
 * statement and therefore atomic: on failure nothing was applied, so restoring the full drained
 * amount to Redis is safe. Restored values are re-drained by the next flush round. Alternatives
 * (durable outbox, pending buffer) are deferred until a proven need.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CounterFlushService {

  private final ClickCounter clickCounter;
  private final LinkRepository repository;
  private final MetricsRecorder metrics;

  /**
   * Drains every Redis counter and applies one batch UPDATE.
   *
   * @return number of link rows updated (0 when there is nothing to flush)
   * @throws IllegalStateException when the database write fails; drained values are restored to
   *     Redis first, so no click is lost silently
   */
  @Transactional
  public int flush() {
    Map<String, Long> drained;
    try {
      drained = clickCounter.drainAll();
    } catch (RuntimeException ex) {
      // Redis down mid-drain: nothing was drained, so
      // counters are still intact in Redis — count the failure and let
      // the scheduler retry next cycle.
      metrics.clickFlushFailed();
      log.error("click drain failed; counters remain in Redis, retry next cycle", ex);
      throw new IllegalStateException("click drain failed; counters remain in Redis", ex);
    }
    if (drained.isEmpty()) {
      return 0;
    }
    try {
      int updated = repository.batchIncrementClicks(drained);
      long totalClicks = drained.values().stream().mapToLong(Long::longValue).sum();
      metrics.clicksFlushed(totalClicks);
      log.info("flushed {} click counters into {} link rows", drained.size(), updated);
      return updated;
    } catch (RuntimeException ex) {
      drained.forEach(clickCounter::restore);
      metrics.clickFlushFailed();
      log.error("click flush failed; restored {} drained counters to Redis", drained.size(), ex);
      throw new IllegalStateException("click flush failed; counters restored to Redis", ex);
    }
  }
}
