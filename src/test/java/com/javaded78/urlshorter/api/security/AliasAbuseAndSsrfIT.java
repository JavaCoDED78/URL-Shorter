package com.javaded78.urlshorter.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
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
 * opens a connection to the stored destination. Internal destinations (loopback, link-local, private
 * ranges) are rejected at {@code create} with a 400; the redirect test uses an unreachable literal
 * to prove a stored link is never fetched server-side.
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
          "a\u001fb",
          "api",
          "actuator",
          "swagger-ui",
          "v3");

  private static final List<String> INTERNAL_DESTINATIONS =
      List.of(
          "http://127.0.0.1/",
          "http://127.0.0.1:8080/actuator",
          "http://[::1]/",
          "http://[::1]:8080/actuator",
          "http://169.254.169.254/latest/meta-data/",
          "http://10.0.0.5/",
          "http://192.168.1.1/",
          "http://172.16.0.1/",
          "http://0.0.0.0/",
          "http://2130706433/");

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void redirectNeverFetchesTheDestinationUrl() {
    // The redirect endpoint answers with a 302 Location only; it never opens a connection to the
    // stored destination. The destination is unreachable (a TEST-NET-3 non-routable literal) — a
    // server-side fetch would hang or error, so a clean 302 proves no fetch happens. (The old
    // local-trap approach required a loopback destination, which internal-address validation now
    // rejects at creation: a reachable local server can no longer be registered as a link target.)
    String destination = "http://203.0.113.42/not-trapped";

    create(destination, "s20ssrf");

    ResponseEntity<String> response =
        restTemplate
            .withRedirects(HttpRedirects.DONT_FOLLOW)
            .getForEntity(url("/s20ssrf"), String.class);

    assertThat(response.getStatusCode().value()).isEqualTo(302);
    assertThat(response.getHeaders().getLocation()).hasToString(destination);
  }

  @Test
  void createToAnInternalAddressIsRejectedWith400() {
    for (String destination : INTERNAL_DESTINATIONS) {
      assertThat(post(destination, "s20ssrf-blocked").getStatusCode().value())
          .as("internal destination %s must be rejected", destination)
          .isEqualTo(400);
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
