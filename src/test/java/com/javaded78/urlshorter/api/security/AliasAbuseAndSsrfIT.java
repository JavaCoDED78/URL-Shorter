package com.javaded78.urlshorter.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Abuse vectors (Tasks 20.2, 20.3) against the running application.
 *
 * <p>20.2 — SSRF posture: the redirect endpoint answers with a {@code 302 Location} only; it never
 * opens a connection to the stored destination. A local trap server proves no request ever arrives.
 *
 * <p>20.3 — alias allowlist is the single defence line and must reject path-traversal strings,
 * reserved routes, whitespace, control characters and non-ASCII input with a plain 400.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class AliasAbuseAndSsrfIT {

  private static final List<String> MALICIOUS_ALIASES =
      List.of(
          "../etc/passwd",
          "a/../b",
          "..",
          "a b",
          "a\tb",
          "café",
          "日本語",
          "a%2fb",
          "%2e%2e",
          "a\\b",
          "ab",
          "api",
          "actuator",
          "swagger-ui",
          "v3");

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void redirectNeverFetchesTheDestinationUrl() throws Exception {
    AtomicInteger trapHits = new AtomicInteger();
    HttpServer trap = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    trap.createContext("/trap", exchange -> trapHits.incrementAndGet());
    trap.start();
    try {
      String destination = "http://127.0.0.1:" + trap.getAddress().getPort() + "/trap";
      create(destination, "s20ssrf");

      ResponseEntity<String> response =
          restTemplate
              .withRedirects(HttpRedirects.DONT_FOLLOW)
              .getForEntity(url("/s20ssrf"), String.class);

      assertThat(response.getStatusCode().value()).isEqualTo(302);
      assertThat(response.getHeaders().getLocation()).hasToString(destination);
      assertThat(trapHits.get()).isZero();
    } finally {
      trap.stop(0);
    }
  }

  @Test
  void maliciousAliasesAreRejectedWith400() {
    for (String alias : MALICIOUS_ALIASES) {
      ResponseEntity<Map> response = post("https://example.com/x", alias);
      assertThat(response.getStatusCode().value())
          .as("alias %s must be rejected", alias)
          .isEqualTo(400);
    }
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
