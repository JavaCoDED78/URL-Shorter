package com.javaded78.urlshorter.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Delete flow (Task 10.4): delete must be consistent between PostgreSQL and Redis — after 204 the
 * link redirects to 404, the cached entry is gone and the pending counter is discarded with the
 * link (Task 10.3 policy).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "shortener.flush.enabled=false")
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class DeleteFlowIT {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private StringRedisTemplate redis;

  private TestRestTemplate rest;

  @BeforeEach
  void setUp() {
    rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
  }

  @Test
  void deleteRemovesLinkEverywhere() {
    create("https://example.com", "del01");
    click("del01", 3);
    assertThat(redis.hasKey("link:del01")).isTrue();
    assertThat(redis.hasKey("clicks:del01")).isTrue();

    ResponseEntity<Void> delete =
        rest.exchange(url("/api/v1/links/del01"), HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);

    assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    // Redirect is gone: 404 from PostgreSQL, no stale cache involved.
    assertThat(redirect("del01").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    // Cached entry evicted (Task 10.1).
    assertThat(redis.hasKey("link:del01")).isFalse();
    // Pending counter discarded with the link (Task 10.3 policy).
    assertThat(redis.hasKey("clicks:del01")).isFalse();
    // Details and stats are gone too.
    assertThat(
            rest.exchange(url("/api/v1/links/del01"), HttpMethod.GET, HttpEntity.EMPTY, Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            rest.exchange(
                    url("/api/v1/links/del01/stats"), HttpMethod.GET, HttpEntity.EMPTY, Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void deleteIsCaseInsensitiveLikeRedirects() {
    create("https://example.com", "del02");

    ResponseEntity<Void> delete =
        rest.exchange(url("/api/v1/links/DEL02"), HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);

    assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(redirect("del02").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void deleteForMissingLinkReturns404() {
    ResponseEntity<Map> response =
        rest.exchange(url("/api/v1/links/nope42"), HttpMethod.DELETE, HttpEntity.EMPTY, Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("status", 404);
  }

  @Test
  void deletedAliasCanBeRecreated() {
    create("https://example.com/old", "del03");
    rest.exchange(url("/api/v1/links/del03"), HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);

    create("https://example.com/new", "del03");

    ResponseEntity<Void> redirect = redirect("del03");
    assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(redirect.getHeaders().getLocation()).hasToString("https://example.com/new");
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
      assertThat(redirect(code).getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }
  }

  private ResponseEntity<Void> redirect(String code) {
    return rest.exchange(url("/" + code), HttpMethod.GET, HttpEntity.EMPTY, Void.class);
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
