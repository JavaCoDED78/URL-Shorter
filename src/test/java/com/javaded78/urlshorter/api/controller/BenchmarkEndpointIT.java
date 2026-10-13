package com.javaded78.urlshorter.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /bench-hit} is a benchmark-only endpoint gated by {@code shortener.benchmark.enabled}
 * (default {@code false}): it must be absent from the default API surface and answer {@code 302}
 * only when the flag is explicitly enabled.
 */
@Tag("integration")
class BenchmarkEndpointIT {

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @AutoConfigureTestRestTemplate
  @Import(TestcontainersConfiguration.class)
  class DisabledByDefault {

    @Autowired TestRestTemplate restTemplate;

    @Test
    void benchHitIsNotPartOfTheDefaultApiSurface() {
      ResponseEntity<String> response = restTemplate.getForEntity("/bench-hit", String.class);

      // Without the mapping, /{code} treats "bench-hit" as a link code;
      // the alias is reserved, so link creation can never claim it.
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = "shortener.benchmark.enabled=true")
  @AutoConfigureTestRestTemplate
  @Import(TestcontainersConfiguration.class)
  class EnabledExplicitly {

    @Autowired TestRestTemplate restTemplate;

    /** Redirect assertions need the raw 302; the default client follows. */
    private TestRestTemplate rest;

    @org.junit.jupiter.api.BeforeEach
    void doNotFollowRedirects() {
      rest =
          restTemplate.withRedirects(
              org.springframework.boot.http.client.HttpRedirects.DONT_FOLLOW);
    }

    @Test
    void benchHitAnswers302WithoutDbLookup() {
      ResponseEntity<String> response = rest.getForEntity("/bench-hit", String.class);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
      assertThat(response.getHeaders().getLocation()).hasToString("https://example.com/bench");
    }
  }
}
