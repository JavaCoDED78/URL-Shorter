package com.javaded78.urlshorter.infra.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.domain.service.CounterFlushService;
import com.javaded78.urlshorter.domain.service.WriteLinkService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Concurrency and correctness (Sprint 16) on real PostgreSQL and Redis.
 *
 * <p>Acceptable delete-vs-redirect semantics (Task 16.5): a redirect racing a delete may
 * legitimately observe the link (302) or not (404) — the API does not promise linearizability for
 * the pair. The invariants are: no 5xx, and once the delete completes, a cache entry repopulated by
 * a racing reader is evicted by the second (post-commit) evict. Residual window: a reader whose
 * PostgreSQL read straddles the delete commit can leave a stale cache entry that self-heals within
 * one cache TTL (accepted per sprint spec).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shortener.flush.enabled=false",
      // 10,000 parallel redirects need a loopback accept queue and
      // connection ceiling above the production defaults
      "server.tomcat.accept-count=10000",
      "server.tomcat.max-connections=10000"
    })
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class ConcurrencyIT {

  private static final int MASSIVE_CLICKS = 10_000;
  private static final int MASSIVE_CREATES = 300;
  private static final int ALIAS_RACERS = 100;

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private CounterFlushService flushService;

  @Autowired private WriteLinkService linkService;

  @Autowired private LinkRepository repository;

  @MockitoSpyBean private LinkRepository spiedRepository;

  private TestRestTemplate rest;

  @BeforeEach
  void setUp() {
    rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
  }

  @Test
  void tenThousandParallelRedirectsAllSucceedAndCountersStayExact() throws Exception {
    create("https://example.com/massive", "conc10k");
    // no explicit warm-up redirect: it would add a click of its own.
    // The first burst request warms the cache via the miss path.

    HttpClient client =
        HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url("/conc10k")))
            .timeout(java.time.Duration.ofSeconds(30))
            .GET()
            .build();

    List<CompletableFuture<HttpResponse<Void>>> futures = new java.util.ArrayList<>(MASSIVE_CLICKS);
    // Bound concurrent connection establishments and retry refused
    // connects: under full-suite load the Windows loopback refuses
    // client-side opens (bare ConnectException) long before the server
    // breaks. A refused connect never reached the server, so retrying
    // the idempotent GET is safe for the harness; server-side behavior
    // is still asserted exactly.
    java.util.concurrent.Semaphore inFlight = new java.util.concurrent.Semaphore(600);
    for (int i = 0; i < MASSIVE_CLICKS; i++) {
      inFlight.acquire();
      futures.add(
          sendWithRetry(client, request, 6).whenComplete((response, error) -> inFlight.release()));
    }
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(240, TimeUnit.SECONDS);

    long succeeded =
        futures.stream()
            .map(CompletableFuture::join)
            .filter(response -> response.statusCode() == 302)
            .count();

    // Task 16.1: every parallel redirect succeeded
    assertThat(succeeded).isEqualTo(MASSIVE_CLICKS);

    // Task 16.2: the atomic flush persists the exact count — no lost clicks
    flushService.flush();
    assertThat(statsClickCount("conc10k")).isEqualTo(MASSIVE_CLICKS);
    var persisted = spiedRepository.findByCodeIgnoreCase("conc10k");
    assertThat(persisted).isPresent();
    assertThat(persisted.get().getClickCount()).isEqualTo(MASSIVE_CLICKS);
  }

  @Test
  void concurrentCreatesProduceUniqueCodesWithoutLosses() throws Exception {
    CountDownLatch ready = new CountDownLatch(MASSIVE_CREATES);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(MASSIVE_CREATES);
    Set<String> codes = ConcurrentHashMap.newKeySet();
    AtomicInteger created = new AtomicInteger();

    for (int i = 0; i < MASSIVE_CREATES; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                ready.countDown();
                try {
                  start.await();
                  ResponseEntity<Map> response =
                      postWithRetry("https://example.com/" + Math.random());
                  if (response.getStatusCode().value() == 201 && response.getBody() != null) {
                    created.incrementAndGet();
                    codes.add((String) response.getBody().get("code"));
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }
    assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();

    assertThat(created.get()).as("no successful create may be lost").isEqualTo(MASSIVE_CREATES);
    assertThat(codes).as("codes must be unique across concurrent creates").hasSize(MASSIVE_CREATES);
    assertThat(repository.count()).isEqualTo(MASSIVE_CREATES);
  }

  @Test
  void collisionRetriesAreBounded() {
    doThrow(new DataIntegrityViolationException("duplicate key"))
        .when(spiedRepository)
        .saveAndFlush(any());

    assertThatThrownBy(() -> linkService.create("https://example.com/boom", null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("collision retries");

    verify(spiedRepository, times(5)).saveAndFlush(any());
  }

  @Test
  void concurrentAliasCreatesYieldSingleWinner() throws Exception {
    CountDownLatch ready = new CountDownLatch(ALIAS_RACERS);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(ALIAS_RACERS);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger conflicts = new AtomicInteger();

    for (int i = 0; i < ALIAS_RACERS; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                ready.countDown();
                try {
                  start.await();
                  int status =
                      post("https://example.com/race", "race-alias").getStatusCode().value();
                  if (status == 201) {
                    successes.incrementAndGet();
                  } else if (status == 409) {
                    conflicts.incrementAndGet();
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }
    assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();

    assertThat(successes).as("exactly one winner").hasValue(1);
    assertThat(conflicts).as("all losers see the conflict").hasValue(ALIAS_RACERS - 1);
  }

  @Test
  void deleteVersusRedirectRaceConverges() throws Exception {
    create("https://example.com/racy", "conc-race");
    rest.getForEntity(url("/conc-race"), String.class); // prime the cache

    // Hold the delete mid-flight so the redirect definitely hits the
    // still-cached link and repopulates the cache between the evict and
    // the commit — the double-evict design must close that window.
    //
    // The delete is held for 1s while the racing redirect completes its
    // full round-trip (cache miss after the first evict → DB read of the
    // not-yet-deleted row → cache write → 302) well before the commit;
    // the second evict then removes the repopulated entry.
    //
    // Known limit (accepted semantics, see sprint spec): if a reader's
    // PostgreSQL read straddles the delete commit, its cache write can
    // land after the second evict, leaving a stale entry that lives at
    // most one cache TTL. Full API linearizability is not required.
    CountDownLatch deleteMidFlight = new CountDownLatch(1);
    CountDownLatch deleteFinished = new CountDownLatch(1);
    // The spy only paces the delete — the actual row removal is a direct
    // SQL DELETE inside the answer, because Mockito cannot callRealMethod
    // on a Spring Data JDK-proxy method.
    doAnswer(
            invocation -> {
              deleteMidFlight.countDown();
              Thread.sleep(1000);
              try {
                jdbcTemplate.update("DELETE FROM links WHERE code = ?", "conc-race");
                return null;
              } finally {
                deleteFinished.countDown();
              }
            })
        .when(spiedRepository)
        .deleteByCode(org.mockito.ArgumentMatchers.anyString());

    java.util.concurrent.atomic.AtomicReference<ResponseEntity<String>> deleteResult =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread deleter =
        Thread.ofVirtual()
            .start(
                () ->
                    deleteResult.set(
                        restTemplate.exchange(
                            url("/api/v1/links/conc-race"),
                            HttpMethod.DELETE,
                            HttpEntity.EMPTY,
                            String.class)));
    assertThat(deleteMidFlight.await(10, TimeUnit.SECONDS)).isTrue();

    int racingStatus = rest.getForEntity(url("/conc-race"), String.class).getStatusCode().value();
    assertThat(racingStatus)
        .as("redirect during an in-flight delete may observe the link")
        .isEqualTo(302);

    deleteFinished.await(10, TimeUnit.SECONDS);
    deleter.join();
    assertThat(deleteResult.get().getStatusCode().value()).isEqualTo(204);

    // convergence: after the pair completes the link is gone everywhere
    assertThat(rest.getForEntity(url("/conc-race"), String.class).getStatusCode().value())
        .isEqualTo(404);
    Boolean linkKeyGone = redisTemplate().hasKey("link:conc-race");
    assertThat(linkKeyGone).isFalse();
  }

  private long statsClickCount(String code) {
    ResponseEntity<Map> stats =
        restTemplate.getForEntity(url("/api/v1/links/" + code + "/stats"), Map.class);
    assertThat(stats.getStatusCode().value()).isEqualTo(200);
    assertThat(stats.getBody()).isNotNull();
    return ((Number) stats.getBody().get("clickCount")).longValue();
  }

  @Autowired private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  private org.springframework.data.redis.core.StringRedisTemplate redisTemplate() {
    return stringRedisTemplate;
  }

  private ResponseEntity<Map> post(String originalUrl) {
    return post(originalUrl, null);
  }

  /**
   * POST create with bounded retry on refused connects only — a refused connect never reached the
   * server, so no row can exist for the attempt; each retry generates a fresh random code.
   */
  private ResponseEntity<Map> postWithRetry(String originalUrl) {
    RuntimeException last = null;
    for (int attempt = 0; attempt < 4; attempt++) {
      try {
        return post(originalUrl);
      } catch (org.springframework.web.client.ResourceAccessException ex) {
        last = ex;
      }
    }
    throw last;
  }

  /**
   * Idempotent GET with bounded retry on refused connects (client-side loopback refusals under
   * load; the request never reached the server).
   */
  private static CompletableFuture<HttpResponse<Void>> sendWithRetry(
      HttpClient client, HttpRequest request, int attemptsLeft) {
    return client
        .sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .handle(
            (response, error) -> {
              if (error == null) {
                return CompletableFuture.completedFuture(response);
              }
              if (attemptsLeft > 0 && hasCause(error, java.net.ConnectException.class)) {
                return sendWithRetry(client, request, attemptsLeft - 1);
              }
              return CompletableFuture.<HttpResponse<Void>>failedFuture(error);
            })
        .thenCompose(future -> future);
  }

  private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }

  private ResponseEntity<Map> post(String originalUrl, String alias) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    Map<String, String> body =
        alias == null
            ? Map.of("originalUrl", originalUrl)
            : Map.of("originalUrl", originalUrl, "alias", alias);
    return restTemplate.exchange(
        url("/api/v1/links"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
  }

  private void create(String originalUrl, String alias) {
    assertThat(post(originalUrl, alias).getStatusCode().value()).isEqualTo(201);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
