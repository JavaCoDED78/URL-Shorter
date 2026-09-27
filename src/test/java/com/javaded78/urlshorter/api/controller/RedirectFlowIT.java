package com.javaded78.urlshorter.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
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
 * Redirect flow against real PostgreSQL (Sprint 5, Tasks 5.1/5.3): the root
 * {@code GET /{code}} endpoint must answer 302/404/410 and must not shadow the
 * actuator health endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class RedirectFlowIT {

    @Autowired
    TestRestTemplate restTemplate;

    /** Redirect assertions need the raw 302; the default client follows. */
    private TestRestTemplate rest;

    @BeforeEach
    void doNotFollowRedirects() {
        rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
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
    void returnsGoneForExpiredLink() throws InterruptedException {
        // Links cannot be created already expired (Task 11.3): create with a
        // near-future expiration and let it lapse.
        post(Map.of(
                "originalUrl", "https://example.com/old",
                "alias", "expired01",
                "expiresAt", Instant.now().plusSeconds(1).toString()));
        Thread.sleep(1100);

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
                RequestEntity.post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                Map.class);
    }
}
