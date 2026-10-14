package com.javaded78.urlshorter.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.api.dto.LinkStatsResponse;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Statistics API flow: stats = persisted DB count + pending Redis counter, so the user never sees a
 * lagging value while the flush interval has not elapsed. The scheduled flush is disabled; the test
 * controls flushing explicitly.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "shortener.flush.enabled=false")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class StatsFlowIT {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private CounterFlushService flushService;

  private TestRestTemplate rest;

  @BeforeEach
  void setUp() {
    rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
  }

  @Test
  void statsAreZeroForFreshLink() {
    create("https://example.com", "stats01");

    ResponseEntity<LinkStatsResponse> response = stats("stats01");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("stats01");
    assertThat(response.getBody().clickCount()).isZero();
  }

  @Test
  void statsIncludePendingClicksBeforeFlush() {
    create("https://example.com", "stats02");
    click("stats02", 4);

    ResponseEntity<LinkStatsResponse> response = stats("stats02");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().clickCount()).isEqualTo(4);
  }

  @Test
  void statsIncludePersistedClicksAfterFlush() {
    create("https://example.com", "stats03");
    click("stats03", 6);
    flushService.flush();

    ResponseEntity<LinkStatsResponse> response = stats("stats03");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().clickCount()).isEqualTo(6);
  }

  @Test
  void statsSumPersistedAndPendingClicks() {
    create("https://example.com", "stats04");
    click("stats04", 6);
    flushService.flush();
    click("stats04", 3);

    ResponseEntity<LinkStatsResponse> response = stats("stats04");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().clickCount()).isEqualTo(9);
  }

  @Test
  void statsAreCaseInsensitiveLikeRedirects() {
    create("https://example.com", "stats05");
    click("stats05", 2);

    ResponseEntity<LinkStatsResponse> response = stats("STATS05");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("stats05");
    assertThat(response.getBody().clickCount()).isEqualTo(2);
  }

  @Test
  void statsForMissingLinkReturn404() {
    // 404 body is a ProblemDetail — read into a Map and assert structurally.
    ResponseEntity<Map> response = statsBody("missing9");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody()).containsKey("status");
  }

  private void create(String originalUrl, String alias) {
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/links"),
            HttpMethod.POST,
            new HttpEntity<>(Map.of("originalUrl", originalUrl, "alias", alias), jsonHeaders()),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  private void click(String code, int times) {
    for (int i = 0; i < times; i++) {
      ResponseEntity<Void> response =
          rest.exchange(url("/" + code), HttpMethod.GET, HttpEntity.EMPTY, Void.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }
  }

  private ResponseEntity<LinkStatsResponse> stats(String code) {
    return rest.exchange(
        url("/api/v1/links/" + code + "/stats"),
        HttpMethod.GET,
        HttpEntity.EMPTY,
        LinkStatsResponse.class);
  }

  /**
   * Stats endpoint for a missing code — the 404 body is a {@code ProblemDetail}, so it is read into
   * a {@link Map} and asserted structurally.
   */
  private ResponseEntity<Map> statsBody(String code) {
    return rest.exchange(
        url("/api/v1/links/" + code + "/stats"), HttpMethod.GET, HttpEntity.EMPTY, Map.class);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private static HttpHeaders jsonHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }
}
