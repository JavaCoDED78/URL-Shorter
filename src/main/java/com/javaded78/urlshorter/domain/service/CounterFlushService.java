package com.javaded78.urlshorter.domain.service;

import java.util.Map;

import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.infra.redis.counter.ClickCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Flushes drained click counters to PostgreSQL (Tasks 8.4-8.6).
 *
 * <p>Recovery strategy (Task 8.6 — explicitly chosen for the MVP): <b>return
 * drained values to Redis</b> ({@code INCRBY}) when the database write fails.
 * The batch UPDATE is a single SQL statement and therefore atomic: on failure
 * nothing was applied, so restoring the full drained amount to Redis is safe.
 * Restored values are re-drained by the next flush round. Alternatives
 * (durable outbox, pending buffer) are deferred until a proven need.
 */
@Service
public class CounterFlushService {

    private static final Logger log = LoggerFactory.getLogger(CounterFlushService.class);

    private final ClickCounter clickCounter;
    private final LinkRepository repository;

    public CounterFlushService(ClickCounter clickCounter, LinkRepository repository) {
        this.clickCounter = clickCounter;
        this.repository = repository;
    }

    /**
     * Drains every Redis counter and applies one batch UPDATE.
     *
     * @return number of link rows updated (0 when there is nothing to flush)
     * @throws IllegalStateException when the database write fails; drained
     *                               values are restored to Redis first, so no
     *                               click is lost silently
     */
    public int flush() {
        Map<String, Long> drained = clickCounter.drainAll();
        if (drained.isEmpty()) {
            return 0;
        }
        String[] codes = drained.keySet().toArray(String[]::new);
        Long[] increments = drained.values().toArray(Long[]::new);
        try {
            int updated = repository.batchIncrementClickCounts(codes, increments);
            log.info("flushed {} click counters into {} link rows", drained.size(), updated);
            return updated;
        } catch (RuntimeException ex) {
            drained.forEach(clickCounter::restore);
            log.error("click flush failed; restored {} drained counters to Redis",
                    drained.size(), ex);
            throw new IllegalStateException("click flush failed; counters restored to Redis", ex);
        }
    }
}
