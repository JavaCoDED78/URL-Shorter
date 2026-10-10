package com.javaded78.urlshorter.config;

import com.javaded78.urlshorter.domain.service.CreateRateLimitSource;
import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import com.javaded78.urlshorter.domain.service.RateLimiter;
import com.javaded78.urlshorter.infra.http.CreateIpRateLimitSource;
import com.javaded78.urlshorter.infra.redis.limiter.NoOpRateLimiter;
import com.javaded78.urlshorter.infra.redis.limiter.RateLimitScript;
import com.javaded78.urlshorter.infra.redis.limiter.RedisRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Rate-limiter bean selection (Task 20.4): exactly one {@link RateLimiter} is bound per process,
 * chosen by {@code shortener.rate-limiter.enabled}.
 *
 * <ul>
 *   <li>enabled = {@code true} — a {@link RedisRateLimiter} protects the create endpoint, keyed per
 *       client IP ({@link CreateRateLimitSource});
 *   <li>enabled = {@code false} (default) or unset — the {@link NoOpRateLimiter} admits everything,
 *       so default deployments are unchanged.
 * </ul>
 *
 * <p>{@link CreateRateLimitSource} (the client-IP resolver) is bound unconditionally — it is needed
 * by {@link com.javaded78.urlshorter.domain.service.WriteLinkService} whether or not the limiter is
 * enabled, and is harmless when the active limiter is the no-op.
 *
 * <p>The limiter conditions are mutually exclusive so the two implementations never clash on the
 * {@link RateLimiter} bean.
 */
@Configuration
public class RateLimitConfig {

  @Bean
  @ConditionalOnProperty(
      prefix = "shortener.rate-limiter",
      value = "enabled",
      havingValue = "true")
  public RateLimiter redisRateLimiter(
      StringRedisTemplate redis,
      RateLimitProperties properties,
      MetricsRecorder metrics,
      RateLimitScript script) {
    return new RedisRateLimiter(
        redis,
        properties.window(),
        properties.maxRequests(),
        properties.failOpen(),
        metrics,
        script);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "shortener.rate-limiter",
      value = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  public RateLimiter defaultRateLimiter() {
    return new NoOpRateLimiter();
  }

  @Bean
  public CreateRateLimitSource createRateLimitSource(RateLimitProperties properties) {
    return new CreateIpRateLimitSource(properties.trustedProxies());
  }
}
