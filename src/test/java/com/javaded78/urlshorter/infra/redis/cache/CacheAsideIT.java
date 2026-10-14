package com.javaded78.urlshorter.infra.redis.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.domain.cache.CachedLink;
import com.javaded78.urlshorter.domain.cache.LinkCache;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Cache-aside behavior: miss hits PostgreSQL and populates Redis, hit serves from Redis without a
 * DB query, TTL is bounded by the remaining link lifetime, expired links are never served from
 * cache.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class CacheAsideIT {

  @Autowired TestRestTemplate restTemplate;

  @Autowired StringRedisTemplate redis;

  @Autowired LinkCache linkCache;

  @MockitoSpyBean LinkRepository repository;

  /** Redirect assertions need the raw 302; the default client follows. */
  private TestRestTemplate rest;

  @BeforeEach
  void doNotFollowRedirects() {
    rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
  }

  @Test
  void missQueriesDbThenPopulatesCache() {
    create("https://example.com/miss", "cache01");

    assertThat(linkCache.get("cache01")).isEmpty();
    redirect("cache01");
    verify(repository, times(1)).findByCodeIgnoreCase("cache01");

    assertThat(linkCache.get("cache01"))
        .contains(new CachedLink("cache01", "https://example.com/miss", null));
  }

  @Test
  void hitServesFromCacheWithoutDbQuery() {
    create("https://example.com/hit", "cache02");
    redirect("cache02");
    redirect("cache02");
    redirect("cache02");

    // one DB lookup total despite three redirects
    verify(repository, times(1)).findByCodeIgnoreCase("cache02");
  }

  @Test
  void cacheEntryHasTtl() {
    create("https://example.com/ttl", "cache03");
    redirect("cache03");

    Long ttl = redis.getExpire("link:cache03", TimeUnit.SECONDS);

    assertThat(ttl).isNotNull().isGreaterThan(0L);
  }

  @Test
  void expiringLinkGetsBoundedTtl() {
    Instant expiresAt = Instant.now().plusSeconds(30);
    create("https://example.com/bounded", "cache04", expiresAt.toString());
    redirect("cache04");

    Long ttl = redis.getExpire("link:cache04", TimeUnit.SECONDS);

    // The cached TTL is bounded by the link's remaining life (~30s), not the configured 1h.
    // Symmetric ±15% jitter spreads it to roughly [0.85, 1.15] · ~30s ≈ [25, 35]s, so the bound
    // needs jitter slack rather than the old hard `<= 30` (positive jitter legitimately writes
    // the entry past 30s); anything near the configured 1h (3600s) would signal a real bug.
    assertThat(ttl).isNotNull().isBetween(20L, 36L);
  }

  @Test
  void neverExpiringLinkGetsConfiguredTtl() {
    create("https://example.com/full", "cache05");
    redirect("cache05");

    Long ttl = redis.getExpire("link:cache05", TimeUnit.SECONDS);

    // Never-expiring links are written with the configured 1h cache TTL, spread by the symmetric
    // ±15% jitter into [0.85, 1.15] · 3600s ≈ [3060, 4140]s. A little slack sits below the floor
    // for the few seconds between the redirect (the TTL write) and this assertion. A strict
    // `> 3500` would be flaky: jitter legitimately drops the written TTL below 3500 (~40% of runs).
    assertThat(ttl).isNotNull().isBetween(3000L, 4200L);
  }

  @Test
  void expiredLinkIsNotCachedAndReturnsGone() throws InterruptedException {
    // Links cannot be created already expired: create with a
    // near-future expiration and let it lapse.
    create("https://example.com/dead", "cache06", Instant.now().plusSeconds(1).toString());
    Thread.sleep(1100);

    ResponseEntity<String> response = redirect("cache06");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    assertThat(redis.hasKey("link:cache06")).isFalse();
  }

  @Test
  void invalidCachedValueFallsBackToDb() {
    create("https://example.com/garbage", "cache07");
    redirect("cache07");
    redis.opsForValue().set("link:cache07", "not-json{{{");

    ResponseEntity<String> response = redirect("cache07");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getLocation()).hasToString("https://example.com/garbage");
  }

  private ResponseEntity<String> redirect(String code) {
    return rest.getForEntity("/" + code, String.class);
  }

  private void create(String url, String alias) {
    create(url, alias, null);
  }

  private void create(String url, String alias, String expiresAt) {
    Map<String, ?> body =
        expiresAt == null
            ? Map.of("originalUrl", url, "alias", alias)
            : Map.of("originalUrl", url, "alias", alias, "expiresAt", expiresAt);
    ResponseEntity<Map> created =
        restTemplate.exchange(
            RequestEntity.post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).body(body),
            Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }
}
