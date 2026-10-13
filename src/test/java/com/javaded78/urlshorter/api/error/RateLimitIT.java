package com.javaded78.urlshorter.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.domain.service.RateLimiter;
import java.util.Map;
import org.junit.jupiter.api.Tag;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Create-endpoint rate limiting (ADR-009): when the {@link RateLimiter} probe denies a create
 * request, the API answers 429 Too Many Requests with a stable {@code ProblemDetail} ({@code type},
 * {@code title}, {@code status}, {@code detail}, {@code instance}) whose {@code type} points at the
 * {@code rate-limited} error category. {@code NoOpRateLimiter} admits everything by default; this
 * test swaps it for a mock that denies, proving the wired enforcement path and the error contract
 * in one shot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class RateLimitIT {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @MockitoBean private RateLimiter rateLimiter;

  @Test
  void rateLimitExceededProduces429ProblemDetail() {
    when(rateLimiter.tryAcquire(any())).thenReturn(false);

    ResponseEntity<Map> response = post(Map.of("originalUrl", "https://example.com"));

    assertThat(response.getStatusCode().value()).isEqualTo(429);
    assertThat(response.getBody())
        .containsKeys("type", "title", "status", "detail", "instance")
        .containsEntry("type", "https://urlshortener.javaded78.dev/errors/rate-limited")
        .containsEntry("title", "Too many requests")
        .containsEntry("status", 429);
    assertThat(response.getBody().get("instance")).isNotNull();
  }

  private ResponseEntity<Map> post(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return restTemplate.exchange(
        url("/api/v1/links"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
