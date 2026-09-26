package com.javaded78.urlshorter.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class LinkCreateFlowIT {

    private static final String API = "/api/links";

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void createsLinkAndReturnsDetails() {
        ResponseEntity<Map> created = post(Map.of("originalUrl", "https://example.com/some/page"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        String code = (String) created.getBody().get("code");
        assertThat(code).hasSize(7);
        assertThat(created.getBody().get("originalUrl")).isEqualTo("https://example.com/some/page");

        ResponseEntity<Map> details = restTemplate.getForEntity(API + "/" + code, Map.class);

        assertThat(details.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(details.getBody()).isNotNull();
        assertThat(details.getBody().get("code")).isEqualTo(code);
        assertThat(details.getBody().get("originalUrl")).isEqualTo("https://example.com/some/page");
        assertThat(details.getBody()).containsKey("createdAt");
        assertThat(details.getBody()).containsKey("expiresAt");
        assertThat(details.getBody().get("clickCount")).isEqualTo(0);
    }

    @Test
    void createsLinkWithCustomAlias() {
        ResponseEntity<Map> created = post(Map.of(
                "originalUrl", "https://example.com",
                "alias", "My-Alias_1"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().get("code")).isEqualTo("my-alias_1");
    }

    @Test
    void createsLinkWithExpiration() {
        String expiresAt = Instant.now().plusSeconds(3600).toString();

        ResponseEntity<Map> created = post(Map.of(
                "originalUrl", "https://example.com",
                "expiresAt", expiresAt));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().get("expiresAt")).isEqualTo(expiresAt);
    }

    @Test
    void rejectsDuplicateAliasWithConflict() {
        post(Map.of("originalUrl", "https://example.com/a", "alias", "dupe-alias"));
        ResponseEntity<Map> conflict = post(Map.of("originalUrl", "https://example.com/b", "alias", "dupe-alias"));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsInvalidOriginalUrlWithBadRequest() {
        ResponseEntity<Map> response = post(Map.of("originalUrl", "not-a-url"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsInvalidAliasWithBadRequest() {
        ResponseEntity<Map> response = post(Map.of(
                "originalUrl", "https://example.com",
                "alias", "bad alias!"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsReservedAliasWithBadRequest() {
        ResponseEntity<Map> response = post(Map.of(
                "originalUrl", "https://example.com",
                "alias", "swagger"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsMissingOriginalUrlWithBadRequest() {
        ResponseEntity<Map> response = post(Map.of("alias", "something"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void returnsNotFoundForUnknownCode() {
        ResponseEntity<Map> response = restTemplate.getForEntity(API + "/no-such-code", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<Map> post(Map<String, ?> body) {
        return restTemplate.exchange(
                RequestEntity.post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                Map.class);
    }
}
