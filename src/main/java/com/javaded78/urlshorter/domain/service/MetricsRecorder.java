package com.javaded78.urlshorter.domain.service;

import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Observability facade (Tasks 13.2, 13.3): business counters and latency
 * timers behind typed methods, so application code never touches Micrometer
 * APIs directly.
 *
 * <p>Counters: {@code links.created}, {@code links.redirected},
 * {@code links.not_found}, {@code links.expired}, {@code links.deleted},
 * {@code cache.hit}, {@code cache.miss}, {@code clicks.incremented},
 * {@code clicks.flushed}, {@code clicks.flush.failed}.
 *
 * <p>Timers: {@code redirect.duration}, {@code postgres.lookup.duration},
 * {@code redis.lookup.duration}.
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

    private final Timer redirectDuration;
    private final Timer postgresLookupDuration;
    private final Timer redisLookupDuration;

    public MetricsRecorder(MeterRegistry registry) {
        linksCreated = registry.counter("links.created");
        linksRedirected = registry.counter("links.redirected");
        linksNotFound = registry.counter("links.not_found");
        linksExpired = registry.counter("links.expired");
        linksDeleted = registry.counter("links.deleted");
        cacheHit = registry.counter("cache.hit");
        cacheMiss = registry.counter("cache.miss");
        clicksIncremented = registry.counter("clicks.incremented");
        clicksIncrementFailed = registry.counter("clicks.increment.failed");
        clicksFlushed = registry.counter("clicks.flushed");
        clicksFlushFailed = registry.counter("clicks.flush.failed");

        redirectDuration = Timer.builder("redirect.duration").register(registry);
        postgresLookupDuration = Timer.builder("postgres.lookup.duration").register(registry);
        redisLookupDuration = Timer.builder("redis.lookup.duration").register(registry);
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
