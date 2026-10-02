package com.javaded78.urlshorter.infra.redis.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.cache.CachedLink;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisLinkCacheTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private final ShortenerProperties properties =
      new ShortenerProperties(
          new ShortenerProperties.Url(2048, List.of("http", "https")),
          new ShortenerProperties.Alias(64, Set.of("api")),
          new ShortenerProperties.Create(5),
          new ShortenerProperties.Cache(Duration.ofHours(1)),
          new ShortenerProperties.Code(7),
          org.springframework.util.unit.DataSize.ofKilobytes(16));

  private final RedisLinkCache cache = new RedisLinkCache(redis, objectMapper, properties);

  RedisLinkCacheTest() {
    when(redis.opsForValue()).thenReturn(ops);
  }

  @Test
  void getReturnsEmptyOnMiss() {
    when(ops.get("link:abc1234")).thenReturn(null);

    assertThat(cache.get("abc1234")).isEmpty();
  }

  @Test
  void getDeserializesHit() throws Exception {
    CachedLink value = new CachedLink("abc1234", "https://example.com", null);
    when(ops.get("link:abc1234")).thenReturn(objectMapper.writeValueAsString(value));

    Optional<CachedLink> result = cache.get("abc1234");

    assertThat(result).contains(value);
  }

  @Test
  void getTreatsInvalidJsonAsMissAndEvicts() {
    when(ops.get("link:abc1234")).thenReturn("not-json{{{");

    assertThat(cache.get("abc1234")).isEmpty();
    verify(redis).delete("link:abc1234");
  }

  @Test
  void getIsCaseInsensitiveOnKeys() {
    when(ops.get("link:abc1234")).thenReturn(null);

    cache.get("ABC1234");

    verify(ops).get("link:abc1234");
  }

  @Test
  void putStoresWithConfiguredTtlForNeverExpiringLink() throws Exception {
    cache.put(new CachedLink("abc1234", "https://example.com", null));

    verify(ops).set(eq("link:abc1234"), anyString(), eq(Duration.ofHours(1)));
  }

  @Test
  void putStoresWithRemainingLifetimeForExpiringLink() throws Exception {
    Instant expiresAt = Instant.now().plusSeconds(30);

    cache.put(new CachedLink("abc1234", "https://example.com", expiresAt));

    verify(ops).set(eq("link:abc1234"), anyString(), any(Duration.class));
  }

  @Test
  void putSkipsAlreadyExpiredLink() {
    cache.put(new CachedLink("abc1234", "https://example.com", Instant.now().minusSeconds(1)));

    verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void evictDeletesKey() {
    cache.evict("abc1234");

    verify(redis).delete("link:abc1234");
  }
}
