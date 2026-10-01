package com.javaded78.urlshorter.api.controller;

import java.net.URI;

import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Benchmark-only redirect shortcut (Sprint 23), active only when
 * {@code shortener.benchmark.enabled=true} — never part of the default API
 * surface.
 *
 * <p>Always answers {@code 302} to a fixed target without a DB/Redis lookup,
 * but records the same metrics as a real cache-hit redirect, so k6 load tests
 * measure pure redirect + click-counting throughput (Tomcat, virtual threads,
 * metrics) without PostgreSQL/Redis in the equation. Kept out of
 * {@link RedirectController} so the production controller carries no
 * benchmark knowledge.
 */
@RestController
@ConditionalOnProperty(name = "shortener.benchmark.enabled", havingValue = "true")
public class BenchmarkController {

    private static final String BENCH_TARGET = "https://example.com/bench";

    private final MetricsRecorder metrics;

    public BenchmarkController(MetricsRecorder metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/bench-hit")
    public ResponseEntity<Void> benchHit() {
        metrics.linkRedirected();
        metrics.cacheHit();
        return metrics.timeRedirect(() ->
                ResponseEntity.status(HttpStatus.FOUND).location(URI.create(BENCH_TARGET)).build());
    }
}
