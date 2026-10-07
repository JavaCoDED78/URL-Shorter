package com.javaded78.urlshorter.infra.redis.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaded78.urlshorter.TestcontainersConfiguration;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end verification of the Redis-backed create rate limiter (Task 20.4): with the limiter
 * enabled at {@code maxRequests=3} per {@code PT30S} window, the first three creates are admitted
 * and the fourth is rejected with {@code 429}, and the denial is recorded on the {@code
 * urlshorter.rateLimiterDenied} counter exposed via {@code /actuator/metrics}.
 *
 * <p>Exercises the real bean through the real Redis container — the metric counter (part of the
 * observability contract) is not covered by {@code RateLimitIT}, which mock-replaces the limiter.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shortener.rate-limiter.enabled=true",
      "shortener.rate-limiter.window=PT30S",
      "shortener.rate-limiter.max-requests=3"
    })
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class RedisRateLimiterIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void fourthCreateInWindowIsRejectedAndCounted() throws Exception {
    // The first three creates admit (window fresh, count 1..3); the fourth hits the ceiling
    // (429).
    ResponseEntity<Map> first = create(Map.of("originalUrl", "https://example.com/a"));
    ResponseEntity<Map> second = create(Map.of("originalUrl", "https://example.com/b"));
    ResponseEntity<Map> third = create(Map.of("originalUrl", "https://example.com/c"));
    ResponseEntity<Map> fourth = create(Map.of("originalUrl", "https://example.com/d"));

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(third.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // The denial is observable on the contract metric.
    assertThat(metric("urlshorter.rateLimiterDenied")).isEqualTo(1);
  }

  private ResponseEntity<Map> create(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return restTemplate.exchange(
        url("/api/v1/links"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
  }

  private JsonNode get(String path) throws Exception {
    ResponseEntity<String> response = restTemplate.getForEntity(url(path), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return MAPPER.readTree(response.getBody());
  }

  private long metric(String name) throws Exception {
    JsonNode body = get("/actuator/metrics/" + name);
    for (JsonNode measurement : body.required("measurements")) {
      if ("COUNT".equals(measurement.required("statistic").asText())) {
        return (long) measurement.required("value").asDouble();
      }
    }
    throw new AssertionError("no COUNT measurement for metric " + name);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
