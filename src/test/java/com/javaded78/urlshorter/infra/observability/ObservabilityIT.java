package com.javaded78.urlshorter.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.domain.service.CounterFlushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Actuator contract (Tasks 13.1–13.3): liveness/readiness/application
 * health with PostgreSQL and Redis dependency checks, business counters
 * (links.*, cache.*, clicks.*) and latency timers
 * (redirect.duration, postgres.lookup.duration, redis.lookup.duration).
 * The scheduled flush is disabled; the test controls flushing explicitly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shortener.flush.enabled=false")
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class ObservabilityIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CounterFlushService flushService;

    @MockitoSpyBean
    private LinkRepository repository;

    private TestRestTemplate rest;

    @BeforeEach
    void setUp() {
        rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
    }

    @Test
    void livenessAndReadinessAreUp() throws Exception {
        assertThat(health("liveness").required("status").asText()).isEqualTo("UP");
        assertThat(health("readiness").required("status").asText()).isEqualTo("UP");
    }

    @Test
    void applicationHealthReportsPostgreSQLAndRedis() throws Exception {
        JsonNode health = get("/actuator/health");
        assertThat(health.required("status").asText()).isEqualTo("UP");
        assertThat(health.required("components").required("db").required("status").asText())
                .isEqualTo("UP");
        assertThat(health.required("components").required("redis").required("status").asText())
                .isEqualTo("UP");
    }

    @Test
    void businessCountersAndTimersAreRecorded() throws Exception {
        long createdBefore = metric("links.created");
        post(Map.of("originalUrl", "https://example.com", "alias", "obs01"));

        redirect("obs01");           // cache miss
        redirect("obs01");           // cache hit
        redirect("no-such-code");    // not found

        assertThat(metric("links.created")).isEqualTo(createdBefore + 1);
        assertThat(metric("links.redirected")).isGreaterThanOrEqualTo(2);
        assertThat(metric("links.not_found")).isGreaterThanOrEqualTo(1);
        assertThat(metric("cache.miss")).isGreaterThanOrEqualTo(1);
        assertThat(metric("cache.hit")).isGreaterThanOrEqualTo(1);
        assertThat(metric("clicks.incremented")).isGreaterThanOrEqualTo(2);
        assertThat(metric("redirect.duration")).isGreaterThanOrEqualTo(2);
        assertThat(metric("redis.lookup.duration")).isGreaterThanOrEqualTo(1);
        assertThat(metric("postgres.lookup.duration")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void expiredAndDeletedLinksAreCounted() throws Exception {
        post(Map.of("originalUrl", "https://example.com", "alias", "obs02",
                "expiresAt", Instant.now().plus(1, ChronoUnit.SECONDS).toString()));
        Thread.sleep(1100);
        redirect("obs02"); // 410

        post(Map.of("originalUrl", "https://example.com", "alias", "obs03"));
        delete("obs03");

        assertThat(metric("links.expired")).isGreaterThanOrEqualTo(1);
        assertThat(metric("links.deleted")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void flushCountersAreRecorded() throws Exception {
        post(Map.of("originalUrl", "https://example.com", "alias", "obs04"));
        redirect("obs04");
        redirect("obs04");
        redirect("obs04");

        flushService.flush();
        assertThat(metric("clicks.flushed")).isGreaterThanOrEqualTo(3);

        doThrow(new DataAccessException("db down") {
        }).when(repository).batchIncrementClickCounts(any(), any());
        redirect("obs04");
        assertThatThrownBy(flushService::flush).isNotNull();
        assertThat(metric("clicks.flush.failed")).isEqualTo(1);
    }

    private JsonNode health(String group) throws Exception {
        return get("/actuator/health/" + group);
    }

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(url(path), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return MAPPER.readTree(response.getBody());
    }

    /** Current value of a counter/timer metric (COUNT statistic). */
    private long metric(String name) throws Exception {
        JsonNode body = get("/actuator/metrics/" + name);
        for (JsonNode measurement : body.required("measurements")) {
            if ("COUNT".equals(measurement.required("statistic").asText())) {
                return (long) measurement.required("value").asDouble();
            }
        }
        throw new AssertionError("no COUNT measurement for metric " + name);
    }

    private void post(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(url("/api/v1/links"),
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void redirect(String code) {
        rest.getForEntity(url("/" + code), String.class);
    }

    private void delete(String code) {
        ResponseEntity<String> response = restTemplate.exchange(url("/api/v1/links/" + code),
                HttpMethod.DELETE, HttpEntity.EMPTY, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
