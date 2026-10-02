package com.javaded78.urlshorter.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.domain.service.LinkService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Unified error contract (Task 11.5): every API error — 400, 404, 409, 410 and 500 — is an RFC 9457
 * {@code ProblemDetail} with the same stable keys ({@code type}, {@code title}, {@code status},
 * {@code detail}, {@code instance}). Internals (SQL, connection URLs, stack traces) never leak into
 * the response (Task 11.4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class ErrorHandlingIT {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @MockitoSpyBean private LinkService linkService;

  @Test
  void missingFieldsProduce400ProblemDetail() {
    ResponseEntity<Map> response = post(Map.of());

    assertProblemContract(response, 400, "Invalid request");
    assertThat(response.getBody()).containsKey("errors");
  }

  @Test
  void invalidUrlProduces400ProblemDetail() {
    ResponseEntity<Map> response = post(Map.of("originalUrl", "not-a-url"));

    assertProblemContract(response, 400, "Invalid request");
  }

  @Test
  void invalidAliasProduces400ProblemDetail() {
    ResponseEntity<Map> response =
        post(Map.of("originalUrl", "https://example.com", "alias", "bad alias!"));

    assertProblemContract(response, 400, "Invalid request");
  }

  @Test
  void expirationInThePastProduces400ProblemDetail() {
    ResponseEntity<Map> response =
        post(
            Map.of(
                "originalUrl",
                "https://example.com",
                "expiresAt",
                Instant.now().minus(1, ChronoUnit.HOURS).toString()));

    assertProblemContract(response, 400, "Invalid request");
    assertThat((String) response.getBody().get("detail")).contains("expiresAt");
  }

  @Test
  void missingLinkProduces404ProblemDetail() {
    ResponseEntity<Map> response =
        restTemplate.exchange(
            url("/api/v1/links/nope11"), HttpMethod.GET, HttpEntity.EMPTY, Map.class);

    assertProblemContract(response, 404, "Link not found");
  }

  @Test
  void retiredUnversionedPathProduces404Not500() {
    // Management API lives under /api/v1 (Sprint 13 versioning); the old
    // unversioned path must be a plain 404, not a misleading 500 from
    // the generic fallback handler.
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> response =
        restTemplate.exchange(
            url("/api/links"),
            HttpMethod.POST,
            new HttpEntity<>(Map.of("originalUrl", "https://example.com"), headers),
            Map.class);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void duplicateAliasProduces409ProblemDetail() {
    post(Map.of("originalUrl", "https://example.com", "alias", "dup01"));
    ResponseEntity<Map> response =
        post(Map.of("originalUrl", "https://other.example", "alias", "dup01"));

    assertProblemContract(response, 409, "Code already taken");
  }

  @Test
  void expiredLinkProduces410ProblemDetail() throws InterruptedException {
    post(
        Map.of(
            "originalUrl",
            "https://example.com",
            "alias",
            "gone01",
            "expiresAt",
            Instant.now().plus(1, ChronoUnit.SECONDS).toString()));
    Thread.sleep(1100);

    ResponseEntity<Map> response =
        restTemplate.exchange(url("/gone01"), HttpMethod.GET, HttpEntity.EMPTY, Map.class);

    assertProblemContract(response, 410, "Link expired");
  }

  @Test
  void unexpectedFailureProduces500ProblemDetailWithoutInternals() {
    // Force an infrastructure-style failure (Task 11.4): the response
    // must be a generic ProblemDetail with no SQL / URLs / message leak.
    doAnswer(
            invocation -> {
              if ("boom9".equals(invocation.getArgument(0))) {
                throw new RuntimeException(
                    "connection refused: jdbc:postgresql://db.internal:5432/urlshortener");
              }
              return invocation.callRealMethod();
            })
        .when(linkService)
        .resolve(anyString());

    ResponseEntity<Map> response =
        restTemplate.exchange(url("/boom9"), HttpMethod.GET, HttpEntity.EMPTY, Map.class);

    assertProblemContract(response, 500, "Internal error");
    String body = response.getBody().toString();
    assertThat(body).doesNotContain("jdbc", "postgresql", "connection refused");
  }

  private ResponseEntity<Map> post(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return restTemplate.exchange(
        url("/api/v1/links"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
  }

  private void assertProblemContract(ResponseEntity<Map> response, int status, String title) {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(response.getBody())
        .containsKeys("type", "title", "status", "detail", "instance")
        .containsEntry("type", "about:blank")
        .containsEntry("title", title)
        .containsEntry("status", status);
    assertThat(response.getBody().get("instance")).isNotNull();
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
