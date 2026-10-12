package com.javaded78.urlshorter.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.infra.redis.flush.CounterFlushService;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * PostgreSQL failure scenarios (Sprint 18) on real PostgreSQL and Redis.
 *
 * <p>Policies:
 *
 * <ul>
 *   <li>18.1 — create with the database down: controlled 503 ProblemDetail (dependency unavailable
 *       — distinct from a 409 business conflict), the cause is logged server-side only, and the
 *       connection-acquisition timeout (5 s, pinned in application.yaml) bounds the wait — the
 *       request can never hang forever.
 *   <li>18.2 — cache miss with the database down: the redirect degrades to the same controlled 503
 *       instead of a misleading generic 500.
 *   <li>18.3 — flush with the database down: drained counters are restored to Redis; once the
 *       database recovers the next flush persists them — no silent click loss.
 * </ul>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "shortener.flush.enabled=false")
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class PostgresFailureIT {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private CounterFlushService flushService;

  @Autowired private StringRedisTemplate redis;

  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  @MockitoSpyBean private LinkRepository repository;

  private TestRestTemplate rest;

  @BeforeEach
  void setUp() {
    rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
  }

  @Test
  void createReturns503WhenDatabaseUnavailable() {
    doThrow(new CannotGetJdbcConnectionException("simulated database outage"))
        .when(repository)
        .save(any(com.javaded78.urlshorter.domain.model.Link.class));

    long started = System.nanoTime();
    ResponseEntity<Map> response = post("https://example.com/db-down", "s18create");
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    // Task 18.1: controlled 503, not a 409 conflict and not a leaked 500
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("title")).isEqualTo("Service unavailable");
    assertThat(response.getBody().get("status")).isEqualTo(503);
    assertThat(response.getBody().get("detail").toString())
        .doesNotContain("jdbc", "postgresql", "CannotGetJdbcConnection");
    // bounded: connection acquisition timeout is pinned at 5 s
    assertThat(elapsedMs).isLessThan(10_000);
  }

  @Test
  void createReturns503WhenTransactionBeginFails() {
    // Real outage mode (found in Sprint 28 live review): with the
    // container down, the failure happens at transaction BEGIN —
    // JpaTransactionManager cannot open an EntityManager — which
    // surfaces as CannotCreateTransactionException, NOT a
    // DataAccessException. A stubbed DataAccessException alone (above)
    // does not reproduce this path.
    doThrow(
            new org.springframework.transaction.CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new CannotGetJdbcConnectionException("simulated database outage")))
        .when(repository)
        .save(any(com.javaded78.urlshorter.domain.model.Link.class));

    ResponseEntity<Map> response = post("https://example.com/db-down-tx", "s18txbegin");

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("title")).isEqualTo("Service unavailable");
    assertThat(response.getBody().get("status")).isEqualTo(503);
    assertThat(response.getBody().get("detail").toString())
        .doesNotContain("jdbc", "postgresql", "CannotGetJdbcConnection", "EntityManager");
  }

  @Test
  void cacheMissReturns503WhenDatabaseUnavailable() {
    create("https://example.com/db-down-miss", "s18miss");
    redis.delete("link:s18miss"); // force the miss → PostgreSQL lookup

    doThrow(new CannotGetJdbcConnectionException("simulated database outage"))
        .when(repository)
        .findByCodeIgnoreCase(org.mockito.ArgumentMatchers.anyString());

    ResponseEntity<Map> response = rest.getForEntity(url("/s18miss"), Map.class);

    // Task 18.2: controlled 503 instead of a misleading generic 500
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("title")).isEqualTo("Service unavailable");
  }

  @Test
  void flushPersistsRestoredCountersAfterDatabaseRecovers() {
    create("https://example.com/db-down-flush", "s18flush");
    rest.getForEntity(url("/s18flush"), String.class);
    rest.getForEntity(url("/s18flush"), String.class);
    rest.getForEntity(url("/s18flush"), String.class);
    rest.getForEntity(url("/s18flush"), String.class);
    rest.getForEntity(url("/s18flush"), String.class);

    AtomicBoolean dbDown = new AtomicBoolean(true);
    // Mockito cannot callRealMethod on a Spring Data JDK-proxy method —
    // the recovery branch applies the batch update via JdbcTemplate.
    doAnswer(
            invocation -> {
              if (dbDown.get()) {
                throw new CannotGetJdbcConnectionException("simulated database outage");
              }
              Map<String, Long> clicks = invocation.getArgument(0);
              int updated = 0;
              for (Map.Entry<String, Long> entry : clicks.entrySet()) {
                updated +=
                    jdbcTemplate.update(
                        "UPDATE links SET click_count = click_count + ? WHERE code = ?",
                        entry.getValue(),
                        entry.getKey());
              }
              return updated;
            })
        .when(repository)
        .batchIncrementClicks(any(Map.class));

    // DB down during flush: counters restored to Redis, failure surfaces
    org.assertj.core.api.Assertions.assertThatThrownBy(flushService::flush)
        .isInstanceOf(IllegalStateException.class);
    assertThat(redis.opsForValue().get("clicks:s18flush")).isEqualTo("5");

    // recovery: next flush persists the restored counters — no silent loss
    dbDown.set(false);
    flushService.flush();

    assertThat(redis.opsForValue().get("clicks:s18flush")).isNull();
    ResponseEntity<Map> stats =
        restTemplate.getForEntity(url("/api/v1/links/s18flush/stats"), Map.class);
    assertThat(stats.getBody()).isNotNull();
    assertThat(((Number) stats.getBody().get("clickCount")).longValue()).isEqualTo(5);
  }

  private ResponseEntity<Map> post(String originalUrl, String alias) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return restTemplate.exchange(
        url("/api/v1/links"),
        HttpMethod.POST,
        new HttpEntity<>(Map.of("originalUrl", originalUrl, "alias", alias), headers),
        Map.class);
  }

  private void create(String originalUrl, String alias) {
    assertThat(post(originalUrl, alias).getStatusCode().value()).isEqualTo(201);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
