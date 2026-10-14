package com.javaded78.urlshorter.infra.redis.flush;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled flush job: moves accumulated Redis clicks into PostgreSQL every {@code
 * shortener.flush.interval} (default PT5S, {@code SHORTENER_FLUSH_INTERVAL}). Disable with {@code
 * shortener.flush.enabled=false} — integration tests drive the flush manually for deterministic
 * assertions.
 */
@Component
@ConditionalOnProperty(
    name = "shortener.flush.enabled",
    havingValue = "true",
    matchIfMissing = true)
@Slf4j
public class ClickFlushJob {

  private final CounterFlushService flushService;

  public ClickFlushJob(CounterFlushService flushService) {
    this.flushService = flushService;
  }

  /**
   * Scheduled flush job: moves accumulated Redis clicks into PostgreSQL every {@code
   * shortener.flush.interval} (default PT5S, {@code SHORTENER_FLUSH_INTERVAL}).
   *
   * <p>Guarded by a distributed ShedLock ({@code @SchedulerLock(name = "clickFlush")}) so a
   * multi-pod deployment drains Redis exactly once per cycle instead of once per replica —
   * otherwise N pods would race the same drain→restore. Disable with {@code
   * shortener.flush.enabled=false}. Integration tests drive the flush manually for deterministic
   * assertions.
   */
  @Scheduled(fixedDelayString = "${shortener.flush.interval:PT5S}")
  @SchedulerLock(name = "clickFlush")
  public void flushClicks() {
    try {
      flushService.flush();
    } catch (RuntimeException ex) {
      // flush() counts the failure via metrics and either restored the
      // drained counters or left them untouched in Redis — keep the
      // scheduler alive; the next cycle retries.
      log.warn("scheduled click flush failed; will retry next cycle", ex);
    }
  }
}
