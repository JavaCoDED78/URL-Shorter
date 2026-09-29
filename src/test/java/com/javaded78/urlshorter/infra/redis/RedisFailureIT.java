package com.javaded78.urlshorter.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.domain.counter.ClickCounter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Redis failure scenarios (Sprint 17) against real PostgreSQL and Redis.
 *
 * <p>Chosen policies, verified here:
 * <ul>
 *   <li>17.1 — Redis down during redirect: the cache degrades to a
 *   pass-through (every access a miss, writes skipped); PostgreSQL remains
 *   the source of truth, so redirects keep working.</li>
 *   <li>17.2 — Redis down during click increment: redirect availability wins,
 *   the click is dropped and counted via the clicks.increment.failed metric.
 *   Click counting is best-effort analytics; the redirect itself is the
 *   product.</li>
 *   <li>17.3 — Redis down during flush: the scheduled job logs, counts the
 *   failure and survives; the next cycle retries and recovers automatically.</li>
 *   <li>17.4 — corrupted cache entry: evicted, PostgreSQL serves the read,
 *   the cache entry is restored.</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shortener.flush.interval=PT1S")
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class RedisFailureIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private StringRedisTemplate spiedRedisTemplate;

    @MockitoSpyBean
    private ClickCounter clickCounter;

    private TestRestTemplate rest;

    @BeforeEach
    void setUp() {
        rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
    }

    @Test
    void redirectFallsBackToPostgresWhenRedisIsDown() {
        create("https://example.com/fallback", "s17down");
        redis.delete("link:s17down");   // force the miss path

        // value operations fail like a dead Redis; plain key commands still work
        ValueOperations<String, String> deadOps = mock(ValueOperations.class);
        doThrow(new RedisConnectionFailureException("simulated outage"))
                .when(deadOps).get(anyString());
        doThrow(new RedisConnectionFailureException("simulated outage"))
                .when(deadOps).set(anyString(), anyString());
        doReturn(1L).when(deadOps).increment(anyString());
        doReturn(deadOps).when(spiedRedisTemplate).opsForValue();

        ResponseEntity<String> response = rest.getForEntity(url("/s17down"), String.class);

        // Task 17.1: cache degrades to pass-through, PostgreSQL serves the redirect
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getLocation()).hasToString("https://example.com/fallback");
    }

    @Test
    void redirectSurvivesClickIncrementFailureAndCountsTheLoss() throws Exception {
        create("https://example.com/lost-click", "s17click");

        ValueOperations<String, String> halfDeadOps = mock(ValueOperations.class);
        doReturn(null).when(halfDeadOps).get(anyString());   // cache always misses
        doThrow(new RedisConnectionFailureException("simulated outage"))
                .when(halfDeadOps).increment(anyString());
        doReturn(halfDeadOps).when(spiedRedisTemplate).opsForValue();

        ResponseEntity<String> response = rest.getForEntity(url("/s17click"), String.class);

        // Task 17.2 policy: availability wins, the click is dropped, not the request
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(metric("clicks.increment.failed")).isGreaterThanOrEqualTo(1);
        assertThat(metric("links.redirected")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void scheduledFlushSurvivesRedisOutageAndRecoversNextCycle() throws Exception {
        create("https://example.com/flush-recovery", "s17flush");

        AtomicBoolean redisDown = new AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (redisDown.get()) {
                throw new RedisConnectionFailureException("simulated outage");
            }
            return invocation.callRealMethod();
        }).when(clickCounter).drainAll();

        rest.getForEntity(url("/s17flush"), String.class);   // accumulate a pending click

        long failedBefore = metric("clicks.flush.failed");
        waitFor(() -> metricUnchecked("clicks.flush.failed") > failedBefore, 30_000);
        assertThat(liveness()).isEqualTo("UP");

        // recovery: next scheduled cycle drains normally
        redisDown.set(false);
        long flushedBefore = metric("clicks.flushed");
        waitFor(() -> metricUnchecked("clicks.flushed") > flushedBefore, 30_000);

        ResponseEntity<Map> stats = restTemplate.getForEntity(
                url("/api/v1/links/s17flush/stats"), Map.class);
        assertThat(stats.getBody()).isNotNull();
        assertThat(((Number) stats.getBody().get("clickCount")).longValue()).isEqualTo(1);
    }

    @Test
    void corruptedCacheEntryFallsBackToPostgresAndRestoresCache() throws Exception {
        create("https://example.com/corrupt", "s17corrupt");
        redis.opsForValue().set("link:s17corrupt", "{not-valid-json");

        ResponseEntity<String> first = rest.getForEntity(url("/s17corrupt"), String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(302);

        // Task 17.4: the bad entry was evicted and the cache restored from PostgreSQL
        String restored = redis.opsForValue().get("link:s17corrupt");
        assertThat(restored).isNotNull().doesNotContain("{not-valid-json");
        JsonNode node = MAPPER.readTree(restored);
        assertThat(node.get("originalUrl").asText()).isEqualTo("https://example.com/corrupt");

        ResponseEntity<String> second = rest.getForEntity(url("/s17corrupt"), String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(302);
    }

    private void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("condition not met within " + timeoutMs + " ms");
    }

    private long metricUnchecked(String name) {
        try {
            return metric(name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long metric(String name) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/metrics/" + name, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode measurements = MAPPER.readTree(response.getBody()).get("measurements");
        for (JsonNode m : measurements) {
            if ("COUNT".equals(m.get("statistic").asText())) {
                return (long) m.get("value").asDouble();
            }
        }
        throw new AssertionError("no COUNT measurement for " + name);
    }

    private String liveness() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/health/liveness", String.class);
        return MAPPER.readTree(response.getBody()).get("status").asText();
    }

    private void create(String originalUrl, String alias) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(url("/api/v1/links"), HttpMethod.POST,
                new HttpEntity<>(Map.of("originalUrl", originalUrl, "alias", alias), headers),
                Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
