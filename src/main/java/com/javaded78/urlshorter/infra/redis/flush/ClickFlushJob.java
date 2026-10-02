package com.javaded78.urlshorter.infra.redis.flush;

import com.javaded78.urlshorter.domain.service.CounterFlushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled flush job (Tasks 8.1, 8.4): moves accumulated Redis clicks into PostgreSQL every {@code
 * shortener.flush.interval} (default PT5S, {@code SHORTENER_FLUSH_INTERVAL}). Disable with {@code
 * shortener.flush.enabled=false} — integration tests drive the flush manually for deterministic
 * assertions.
 */
@Component
@ConditionalOnProperty(
    name = "shortener.flush.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ClickFlushJob {

  private static final Logger log = LoggerFactory.getLogger(ClickFlushJob.class);

  private final CounterFlushService flushService;

  public ClickFlushJob(CounterFlushService flushService) {
    this.flushService = flushService;
  }

  @Scheduled(fixedDelayString = "${shortener.flush.interval:PT5S}")
  public void flushClicks() {
    try {
      flushService.flush();
    } catch (RuntimeException ex) {
      // flush() counts the failure via metrics and either restored the
      // drained counters or left them untouched in Redis — keep the
      // scheduler alive; the next cycle retries (Task 17.3).
      log.warn("scheduled click flush failed; will retry next cycle", ex);
    }
  }
}
