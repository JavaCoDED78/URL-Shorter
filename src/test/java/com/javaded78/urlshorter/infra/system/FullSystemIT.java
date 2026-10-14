package com.javaded78.urlshorter.infra.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.infra.redis.flush.CounterFlushService;
import java.util.Map;
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

/**
 * Full-system lifecycle check: one end-to-end chain through every subsystem on real PostgreSQL and
 * Redis — create → redirect × N → stats (pending) → flush → stats (persisted) → delete → 404
 * everywhere → Redis evicted → alias reusable. The class gets its own application context, hence
 * fresh containers and clean state.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "shortener.flush.enabled=false")
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class FullSystemIT {

  private static final String ALIAS = "s15full";

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private CounterFlushService flushService;

  @Autowired private StringRedisTemplate redis;

  private TestRestTemplate rest;

  @BeforeEach
  void setUp() {
    rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
  }

  @Test
  void fullLifecycleCreateRedirectFlushStatsDeleteAndRecreate() {
    // create → PostgreSQL is the source of truth
    ResponseEntity<Map> created = post("https://example.com/target", ALIAS);
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    assertThat(created.getBody()).isNotNull();
    assertThat(created.getBody().get("code")).isEqualTo(ALIAS);
    assertThat(created.getBody().get("originalUrl")).isEqualTo("https://example.com/target");

    // redirect × 3 → 302 + Redis counter
    for (int i = 0; i < 3; i++) {
      ResponseEntity<String> redirect = rest.getForEntity(url("/" + ALIAS), String.class);
      assertThat(redirect.getStatusCode().value()).isEqualTo(302);
      assertThat(redirect.getHeaders().getLocation()).hasToString("https://example.com/target");
    }

    // stats reflect pending Redis clicks before the flush
    assertThat(statsClickCount()).isEqualTo(3);

    // flush → drained into PostgreSQL, stats must not drop
    flushService.flush();
    assertThat(statsClickCount()).isEqualTo(3);
    assertThat(redis.opsForValue().get("clicks:" + ALIAS)).isNull();

    // delete → gone everywhere, cache and counter evicted
    ResponseEntity<String> delete =
        restTemplate.exchange(
            url("/api/v1/links/" + ALIAS), HttpMethod.DELETE, HttpEntity.EMPTY, String.class);
    assertThat(delete.getStatusCode().value()).isEqualTo(204);

    assertThat(rest.getForEntity(url("/" + ALIAS), String.class).getStatusCode().value())
        .isEqualTo(404);
    assertThat(
            restTemplate
                .getForEntity(url("/api/v1/links/" + ALIAS), Map.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(redis.hasKey("link:" + ALIAS)).isFalse();
    assertThat(redis.hasKey("clicks:" + ALIAS)).isFalse();

    // the alias is reusable after deletion
    ResponseEntity<Map> recreated = post("https://example.com/new-target", ALIAS);
    assertThat(recreated.getStatusCode().value()).isEqualTo(201);
    assertThat(recreated.getBody()).isNotNull();
    assertThat(recreated.getBody().get("originalUrl")).isEqualTo("https://example.com/new-target");
    assertThat(recreated.getBody().get("clickCount")).isEqualTo(0);
  }

  private long statsClickCount() {
    ResponseEntity<Map> stats =
        restTemplate.getForEntity(url("/api/v1/links/" + ALIAS + "/stats"), Map.class);
    assertThat(stats.getStatusCode().value()).isEqualTo(200);
    assertThat(stats.getBody()).isNotNull();
    return ((Number) stats.getBody().get("clickCount")).longValue();
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

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
