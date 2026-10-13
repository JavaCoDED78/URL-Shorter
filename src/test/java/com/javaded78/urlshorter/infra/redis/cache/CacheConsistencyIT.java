package com.javaded78.urlshorter.infra.redis.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Cache consistency against real PostgreSQL and Redis.
 *
 * <ul>
 *   <li>19.1 — business TTL outlives the cache entry: when the Redis entry expires first the
 *       redirect re-reads PostgreSQL and repopulates the cache; the user-visible behavior is
 *       unchanged.
 *   <li>19.2 — the reverse: an entry may still be in Redis after the link's business expiration;
 *       {@code expiresAt} is re-checked on every hit and the redirect returns 410 without touching
 *       PostgreSQL or counting a click.
 *   <li>19.3 — delete racing redirect is covered by {@code ConcurrencyIT}; the documented semantics
 *       are an immediate 404/302 converging within one cache TTL.
 *   <li>19.4 — the wire format is plain JSON (never JVM serialization); a reader must tolerate
 *       entries written by a newer version with extra fields, so cache entries keep serving across
 *       application upgrades.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class CacheConsistencyIT {

  private static final String KEY_PREFIX = "link:";

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private StringRedisTemplate redis;

  @MockitoSpyBean private LinkRepository repository;

  private TestRestTemplate rest;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
  }

  @Test
  void cacheEntryExpiringFirstTriggersDbLookupAndRepopulate() throws Exception {
    String alias = "s19rep";
    create("https://example.com/cache-first", alias, Instant.now().plus(Duration.ofHours(1)));

    assertThat(rest.getForEntity(url("/" + alias), String.class).getStatusCode().value())
        .isEqualTo(302);

    // force the cache entry to die long before the business expiry
    redis.expire(KEY_PREFIX + alias, Duration.ofSeconds(1));
    Thread.sleep(1200);
    assertThat(redis.opsForValue().get(KEY_PREFIX + alias)).isNull();

    assertThat(rest.getForEntity(url("/" + alias), String.class).getStatusCode().value())
        .isEqualTo(302);

    // both redirects needed PostgreSQL: miss → DB, then repopulate miss → DB
    verify(repository, times(2)).findByCodeIgnoreCase(alias);
    // and the cache was repopulated with a positive TTL
    String repopulated = redis.opsForValue().get(KEY_PREFIX + alias);
    assertThat(repopulated).isNotNull();
    assertThat(redis.getExpire(KEY_PREFIX + alias)).isPositive();
  }

  @Test
  void expiredLinkRejectedFromCacheWithoutDbLookupOrClick() {
    String alias = "s19exp";
    create("https://example.com/expired-cache", alias, Instant.now().plus(Duration.ofHours(1)));
    assertThat(rest.getForEntity(url("/" + alias), String.class).getStatusCode().value())
        .isEqualTo(302);

    // simulate a stale entry that outlived the link: business expiry in
    // the past but a long Redis TTL remaining
    redis
        .opsForValue()
        .set(
            KEY_PREFIX + alias,
            """
                {"code":"%s","originalUrl":"https://example.com/expired-cache",\
"expiresAt":"2020-01-01T00:00:00Z"}"""
                .formatted(alias),
            Duration.ofHours(1));

    ResponseEntity<Map> response = restTemplate.getForEntity(url("/" + alias), Map.class);
    assertThat(response.getStatusCode().value()).isEqualTo(410);

    // served from the cached expiry check: no second DB lookup...
    verify(repository, times(1)).findByCodeIgnoreCase(alias);
    // ...and the stale redirect was never counted as a click
    ResponseEntity<Map> stats =
        restTemplate.getForEntity(url("/api/v1/links/" + alias + "/stats"), Map.class);
    assertThat(((Number) stats.getBody().get("clickCount")).longValue()).isEqualTo(1);
  }

  @Test
  void cacheEntryWithExtraFieldsStillDeserializes() {
    String alias = "s19fwd";
    create("https://example.com/future-fields", alias, null);
    assertThat(rest.getForEntity(url("/" + alias), String.class).getStatusCode().value())
        .isEqualTo(302);

    // an entry written by a newer application version with extra fields
    redis
        .opsForValue()
        .set(
            KEY_PREFIX + alias,
            """
                {"code":"%s","originalUrl":"https://example.com/future-fields",\
"expiresAt":null,"schemaVersion":2,"addedByFutureRelease":"x"}"""
                .formatted(alias),
            Duration.ofHours(1));

    assertThat(rest.getForEntity(url("/" + alias), String.class).getStatusCode().value())
        .isEqualTo(302);
    // forward-compatible read: served from cache, no DB fallback
    verify(repository, times(1)).findByCodeIgnoreCase(alias);
  }

  private void create(String originalUrl, String alias, Instant expiresAt) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    Map<String, Object> body =
        expiresAt != null
            ? Map.of("originalUrl", originalUrl, "alias", alias, "expiresAt", expiresAt.toString())
            : Map.of("originalUrl", originalUrl, "alias", alias);
    ResponseEntity<Map> response =
        restTemplate.exchange(
            url("/api/v1/links"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
