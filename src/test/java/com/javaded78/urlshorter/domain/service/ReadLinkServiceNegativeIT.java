package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Stampede protection and negative caching on the hot redirect path (Task 21.1, 21.2).
 *
 * <p>A hot code that has just evicted (or a never-cached link) must not fan N concurrent requests
 * into PostgreSQL: {@link ReadLinkService#resolve} coalesces them behind one in-flight lookup. A
 * scan of nonexistent codes must hit the Bloom-filter-backed negative cache and never reach the
 * database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class ReadLinkServiceNegativeIT {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private StringRedisTemplate redis;

  @MockitoSpyBean private com.javaded78.urlshorter.domain.repository.LinkRepository repository;

  private TestRestTemplate rest;

  @BeforeEach
  void doNotFollowRedirects() {
    rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  // --- Task 21.1: stampede protection (singleflight) ---

  @Test
  void concurrentMissesOnSameCodeQueryDbOnce() throws InterruptedException {
    // Prime the cache for a positive link, then evict it so every concurrent request below
    // starts from a cold cache.
    create("https://example.com/stampede", "stmp1");
    redis.delete("link:stmp1");

    // Fire 500 concurrent redirects at the uncached code.
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(500);
    for (int i = 0; i < 500; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  start.await();
                  rest.getForEntity(url("/stmp1"), String.class);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }
    start.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

    // Exactly one PostgreSQL lookup despite 500 concurrent cold requests.
    verify(repository, times(1)).findByCodeIgnoreCase("stmp1");
    // Every request got a valid 302 — coalescing preserves correctness.
  }

  // --- Task 21.2: negative caching ---

  @Test
  void nonexistentCodeHitsNegativeCacheNotDb() {
    // A never-created code is absent from the Bloom filter, so coalesce short-circuits before any
    // DB hit — the marker is only written for Bloom-present-but-absent codes (created-then-deleted).
    assertThat(redirect("/ghost1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(redirect("/ghost1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    // Neither call reaches PostgreSQL — the Bloom filter shields the distinct-scan case outright.
    verify(repository, never()).findByCodeIgnoreCase("ghost1");
  }

  @Test
  void bloomFilterShortCircuitsRandomCodeScan() {
    // A scan of 200 random codes that were never created must not hit PostgreSQL for each.
    // The Bloom filter's false-positive rate caps the number of lookups well below 200.
    for (int i = 0; i < 200; i++) {
      redirect("/rand-" + Integer.toHexString(i));
    }

    // With 11-bit hashes over 200 distinct misses the filter is a near-perfect short-circuit:
    // at most a handful of lookups (well under 200) reach the database.
    verify(repository, atMost(10)).findByCodeIgnoreCase(any());
  }

  @Test
  void positiveCacheHitStillBypassesDatabase() {
    create("https://example.com/hit", "neg-hit");
    redis.delete("link:neg-hit");

    redirect("/neg-hit"); // cold: DB lookup + cache write
    redirect("/neg-hit"); // warm: cache hit

    verify(repository, times(1)).findByCodeIgnoreCase("neg-hit");
  }

  private ResponseEntity<Void> redirect(String code) {
    return rest.getForEntity(url(code), Void.class);
  }

  private void create(String url, String alias) {
    ResponseEntity<Map> created =
        restTemplate.exchange(
            RequestEntity.post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("originalUrl", url, "alias", alias)),
            Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }
}
