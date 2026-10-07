package com.javaded78.urlshorter.domain.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Observability facade (Tasks 13.2, 13.3): business counters and latency timers behind typed
 * methods, so application code never touches Micrometer APIs directly.
 *
 * <p>Counters: {@code urlshorter.linksCreated}, {@code urlshorter.linksRedirected}, {@code
 * urlshorter.linksNotFound}, {@code urlshorter.linksExpired}, {@code urlshorter.linksDeleted},
 * {@code urlshorter.cacheHit}, {@code urlshorter.cacheMiss}, {@code urlshorter.clicksIncremented},
 * {@code urlshorter.clicksFlushed}, {@code urlshorter.clicksFlushFailed}, {@code
 * urlshorter.clicksIncrementFailed}, {@code urlshorter.rateLimiterDenied}.
 *
 * <p>Timers: {@code urlshorter.redirectDuration}, {@code urlshorter.postgresLookupDuration}, {@code
 * urlshorter.redisLookupDuration}.
 *
 * <p>The {@code urlshorter.} prefix is a Micrometer namespace separator that converts to underscore
 * in Prometheus format (e.g. {@code urlshorter.linksCreated} → {@code
 * urlshorter_linksCreated_total}).
 */
@Component
public class MetricsRecorder {

  private final Counter linksCreated;
  private final Counter linksRedirected;
  private final Counter linksNotFound;
  private final Counter linksExpired;
  private final Counter linksDeleted;
  private final Counter cacheHit;
  private final Counter cacheMiss;
  private final Counter clicksIncremented;
  private final Counter clicksIncrementFailed;
  private final Counter clicksFlushed;
  private final Counter clicksFlushFailed;
  private final Counter rateLimiterDenied;

  private final Timer redirectDuration;
  private final Timer postgresLookupDuration;
  private final Timer redisLookupDuration;

  public MetricsRecorder(MeterRegistry registry) {
    linksCreated = registry.counter("urlshorter.linksCreated");
    linksRedirected = registry.counter("urlshorter.linksRedirected");
    linksNotFound = registry.counter("urlshorter.linksNotFound");
    linksExpired = registry.counter("urlshorter.linksExpired");
    linksDeleted = registry.counter("urlshorter.linksDeleted");
    cacheHit = registry.counter("urlshorter.cacheHit");
    cacheMiss = registry.counter("urlshorter.cacheMiss");
    clicksIncremented = registry.counter("urlshorter.clicksIncremented");
    clicksIncrementFailed = registry.counter("urlshorter.clicksIncrementFailed");
    clicksFlushed = registry.counter("urlshorter.clicksFlushed");
    clicksFlushFailed = registry.counter("urlshorter.clicksFlushFailed");
    rateLimiterDenied = registry.counter("urlshorter.rateLimiterDenied");

    redirectDuration = Timer.builder("urlshorter.redirectDuration").register(registry);
    postgresLookupDuration = Timer.builder("urlshorter.postgresLookupDuration").register(registry);
    redisLookupDuration = Timer.builder("urlshorter.redisLookupDuration").register(registry);
  }

  public void linkCreated() {
    linksCreated.increment();
  }

  public void linkRedirected() {
    linksRedirected.increment();
  }

  public void linkNotFound() {
    linksNotFound.increment();
  }

  public void linkExpired() {
    linksExpired.increment();
  }

  public void linkDeleted() {
    linksDeleted.increment();
  }

  public void cacheHit() {
    cacheHit.increment();
  }

  public void cacheMiss() {
    cacheMiss.increment();
  }

  public void clickIncremented() {
    clicksIncremented.increment();
  }

  /** A click could not be counted because Redis was unavailable (17.2). */
  public void clickIncrementFailed() {
    clicksIncrementFailed.increment();
  }

  public void clicksFlushed(long count) {
    clicksFlushed.increment(count);
  }

  public void clickFlushFailed() {
    clicksFlushFailed.increment();
  }

  /** A create-rate-limit probe was denied (Task 20.4). */
  public void rateLimiterDenied() {
    rateLimiterDenied.increment();
  }

  /** Times a redirect resolution end-to-end (controller → service). */
  public <T> T timeRedirect(Supplier<T> operation) {
    return redirectDuration.wrap(operation).get();
  }

  /** Times a PostgreSQL link lookup (redirect cache-miss path). */
  public <T> T timePostgresLookup(Supplier<T> operation) {
    return postgresLookupDuration.wrap(operation).get();
  }

  /** Times a Redis cache lookup. */
  public <T> T timeRedisLookup(Supplier<T> operation) {
    return redisLookupDuration.wrap(operation).get();
  }
}
