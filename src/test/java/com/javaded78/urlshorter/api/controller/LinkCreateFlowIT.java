package com.javaded78.urlshorter.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.api.dto.LinkResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class LinkCreateFlowIT {

  private static final String API = "/api/v1/links";

  @Autowired TestRestTemplate restTemplate;

  @Test
  void createsLinkAndReturnsDetails() {
    ResponseEntity<LinkResponse> created =
        post(Map.of("originalUrl", "https://example.com/some/page"));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).isNotNull();
    LinkResponse link = created.getBody();
    assertThat(link.code()).hasSize(7);
    assertThat(link.originalUrl()).isEqualTo("https://example.com/some/page");

    ResponseEntity<LinkResponse> details =
        restTemplate.getForEntity(API + "/" + link.code(), LinkResponse.class);

    assertThat(details.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(details.getBody()).isNotNull();
    assertThat(details.getBody().code()).isEqualTo(link.code());
    assertThat(details.getBody().originalUrl()).isEqualTo("https://example.com/some/page");
    assertThat(details.getBody().createdAt()).isNotNull();
    assertThat(details.getBody().expiresAt()).isNull();
    assertThat(details.getBody().clickCount()).isZero();
  }

  @Test
  void createsLinkWithCustomAlias() {
    ResponseEntity<LinkResponse> created =
        post(Map.of("originalUrl", "https://example.com", "alias", "My-Alias_1"));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).isNotNull();
    assertThat(created.getBody().code()).isEqualTo("my-alias_1");
  }

  @Test
  void createsLinkWithExpiration() {
    String expiresAt = Instant.now().plusSeconds(3600).toString();

    ResponseEntity<LinkResponse> created =
        post(Map.of("originalUrl", "https://example.com", "expiresAt", expiresAt));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).isNotNull();
    assertThat(created.getBody().expiresAt().toString()).isEqualTo(expiresAt);
  }

  @Test
  void rejectsDuplicateAliasWithConflict() {
    post(Map.of("originalUrl", "https://example.com/a", "alias", "dupe-alias"));
    ResponseEntity<Map<String, Object>> conflict =
        postRaw(Map.of("originalUrl", "https://example.com/b", "alias", "dupe-alias"));

    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void concurrentDuplicateAliasCreatesYieldOneSuccessAndOneConflict() throws Exception {
    // Task 15.6: two parallel creates with the same alias — the database
    // UNIQUE constraint is the final arbiter, so exactly one request can
    // win regardless of timing.
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<ResponseEntity<Map<String, Object>>> first =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<ResponseEntity<Map<String, Object>>> second =
        new java.util.concurrent.atomic.AtomicReference<>();

    Thread t1 = Thread.ofVirtual().start(() -> awaitAndCreate(start, first));
    Thread t2 = Thread.ofVirtual().start(() -> awaitAndCreate(start, second));
    start.countDown();
    t1.join();
    t2.join();

    assertThat(
            java.util.List.of(
                first.get().getStatusCode().value(), second.get().getStatusCode().value()))
        .containsExactlyInAnyOrder(201, 409);
  }

  private void awaitAndCreate(
      java.util.concurrent.CountDownLatch start,
      java.util.concurrent.atomic.AtomicReference<ResponseEntity<Map<String, Object>>> result) {
    try {
      start.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    result.set(
        postRaw(Map.of("originalUrl", "https://example.com/concurrent", "alias", "race-alias")));
  }

  @Test
  void rejectsInvalidOriginalUrlWithBadRequest() {
    ResponseEntity<Map<String, Object>> response = postRaw(Map.of("originalUrl", "not-a-url"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rejectsInvalidAliasWithBadRequest() {
    ResponseEntity<Map<String, Object>> response =
        postRaw(
            Map.of(
                "originalUrl", "https://example.com",
                "alias", "bad alias!"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rejectsReservedAliasWithBadRequest() {
    ResponseEntity<Map<String, Object>> response =
        postRaw(
            Map.of(
                "originalUrl", "https://example.com",
                "alias", "swagger"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rejectsMissingOriginalUrlWithBadRequest() {
    ResponseEntity<Map<String, Object>> response = postRaw(Map.of("alias", "something"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void returnsNotFoundForUnknownCode() {
    ResponseEntity<Map> response = restTemplate.getForEntity(API + "/no-such-code", Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private ResponseEntity<LinkResponse> post(Map<String, ?> body) {
    return restTemplate.exchange(
        RequestEntity.post(API).contentType(MediaType.APPLICATION_JSON).body(body),
        LinkResponse.class);
  }

  /**
   * Raw POST that deserializes into a {@link Map}: used where the response may be an error {@code
   * problem+json} (e.g. the concurrent-duplicate-alias 409), which {@link LinkResponse} cannot be
   * deserialized into.
   */
  private ResponseEntity<Map<String, Object>> postRaw(Map<String, ?> body) {
    return restTemplate.exchange(
        RequestEntity.post(API).contentType(MediaType.APPLICATION_JSON).body(body),
        new ParameterizedTypeReference<Map<String, Object>>() {});
  }
}
