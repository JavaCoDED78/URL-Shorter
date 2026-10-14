package com.javaded78.urlshorter.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.api.SteppingClock;
import com.javaded78.urlshorter.api.SteppingClockConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

/**
 * Redirect flow against real PostgreSQL: the root {@code GET /{code}} endpoint must answer
 * 302/404/410 and must not shadow the actuator health endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({TestcontainersConfiguration.class, SteppingClockConfig.class})
@Tag("integration")
class RedirectFlowIT {

  @Autowired TestRestTemplate restTemplate;

  /** Deterministic clock shared with the app context: advancing it expires links instantly. */
  @Autowired SteppingClock clock;

  /** Redirect assertions need the raw 302; the default client follows. */
  private TestRestTemplate rest;

  @BeforeEach
  void doNotFollowRedirects() {
    rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
  }

  @AfterEach
  void resetClock() {
    // Restore the real clock so the stepped-forward instant never leaks into the next test.
    clock.reset();
  }

  @Test
  void redirectsActiveLinkWithFoundAndLocation() {
    post(Map.of("originalUrl", "https://example.com/page", "alias", "redir01"));

    ResponseEntity<String> response = rest.getForEntity("/redir01", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getLocation()).hasToString("https://example.com/page");
  }

  @Test
  void redirectsAreCaseInsensitiveForAliases() {
    post(Map.of("originalUrl", "https://example.com/case", "alias", "My-Alias"));

    ResponseEntity<String> response = rest.getForEntity("/MY-ALIAS", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getLocation()).hasToString("https://example.com/case");
  }

  @Test
  void redirectsActiveLinkWithGeneratedMixedCaseCode() {
    // Regression: generated Base62 codes are mixed-case (e.g. eqAYMsH); without the markExists
    // lowercase normalization the generated code is wrongly treated as absent -> 404 on redirect.
    ResponseEntity<Map> created = post(Map.of("originalUrl", "https://example.com/generated"));

    Object codeObj = created.getBody().get("code");
    String code = (String) codeObj;

    assertThat(code)
        .as("generated code is mixed-case and must resolve on the /{code} redirect")
        .isNotEmpty()
        .isNotEqualTo(code.toLowerCase());

    ResponseEntity<String> response = rest.getForEntity("/" + code, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
  }

  @Test
  void redirectsNeverExpiringLink() {
    post(Map.of("originalUrl", "https://example.com/forever", "alias", "noexpire"));

    ResponseEntity<String> response = rest.getForEntity("/noexpire", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
  }

  @Test
  void returnsNotFoundForUnknownCode() {
    ResponseEntity<String> response = rest.getForEntity("/no-such-code", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void returnsGoneForExpiredLink() {
    // Cannot create an already-expired link: create with a
    // future expiration (relative to the deterministic clock) then step
    // the clock past it, instead of Thread.sleep racing the real clock.
    Instant expiresAt = clock.instant().plusSeconds(30);
    post(
        Map.of(
            "originalUrl", "https://example.com/old",
            "alias", "expired01",
            "expiresAt", expiresAt.toString()));

    // Advance time so the link has lapsed.
    clock.step(Duration.ofSeconds(31));

    ResponseEntity<String> response = rest.getForEntity("/expired01", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
  }

  @Test
  void rootMappingDoesNotShadowActuatorHealth() {
    ResponseEntity<Map> response = rest.getForEntity("/actuator/health", Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("status", "UP");
  }

  private ResponseEntity<Map> post(Map<String, ?> body) {
    return restTemplate.exchange(
        RequestEntity.post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).body(body),
        Map.class);
  }
}
