package com.javaded78.urlshorter.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.api.dto.LinkResponse;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
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

/**
 * Idempotency for the create endpoint (Idempotency-Key). A client retry after a timeout must replay
 * the original result (same code, 201) instead of creating a second link for the same URL; two
 * concurrent requests carrying the same key must converge on exactly one link.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class IdempotencyIT {

  private static final String API = "/api/v1/links";

  @Autowired TestRestTemplate restTemplate;

  @Test
  void retryWithSameKeyReplaysOriginalResultNotSecondLink() {
    ResponseEntity<LinkResponse> first =
        postWithKey(Map.of("originalUrl", "https://example.com/idempotent"), "key-replay");

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(first.getBody()).isNotNull();
    String code = first.getBody().code();

    // A retried request after the first timed out carries the same key — it must not create a
    // second link, it must replay the original 201 with the original code.
    ResponseEntity<Map<String, Object>> replay =
        postRawWithKey(Map.of("originalUrl", "https://example.com/idempotent"), "key-replay");

    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replay.getBody()).isNotNull();
    assertThat(replay.getBody().get("code")).isEqualTo(code);
  }

  @Test
  void concurrentSameKeyCreatesYieldOneLink() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<ResponseEntity<Map<String, Object>>> first = new AtomicReference<>();
    AtomicReference<ResponseEntity<Map<String, Object>>> second = new AtomicReference<>();

    Thread t1 = Thread.ofVirtual().start(() -> awaitAndCreate(start, first));
    Thread t2 = Thread.ofVirtual().start(() -> awaitAndCreate(start, second));
    start.countDown();
    t1.join();
    t2.join();

    assertThat(first.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(first.get().getBody()).isNotNull();
    assertThat(second.get().getBody()).isNotNull();
    // Both converge on the same generated code — the second replayed the first's result.
    assertThat(first.get().getBody().get("code")).isEqualTo(second.get().getBody().get("code"));
  }

  @Test
  void distinctKeysProduceDistinctLinks() {
    ResponseEntity<Map<String, Object>> a =
        postRawWithKey(Map.of("originalUrl", "https://example.com/a"), "key-a");
    ResponseEntity<Map<String, Object>> b =
        postRawWithKey(Map.of("originalUrl", "https://example.com/b"), "key-b");

    assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(a.getBody().get("code")).isNotEqualTo(b.getBody().get("code"));
  }

  private void awaitAndCreate(
      CountDownLatch start, AtomicReference<ResponseEntity<Map<String, Object>>> result) {
    try {
      start.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    result.set(
        postRawWithKey(Map.of("originalUrl", "https://example.com/idempotent"), "key-concurrent"));
  }

  private ResponseEntity<LinkResponse> postWithKey(Map<String, ?> body, String key) {
    return restTemplate.exchange(
        RequestEntity.post(API)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body),
        LinkResponse.class);
  }

  private ResponseEntity<Map<String, Object>> postRawWithKey(Map<String, ?> body, String key) {
    return restTemplate.exchange(
        RequestEntity.post(API)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body),
        new ParameterizedTypeReference<Map<String, Object>>() {});
  }
}
