package com.javaded78.urlshorter.config;

import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import com.javaded78.urlshorter.domain.service.RateLimiter;
import com.javaded78.urlshorter.infra.redis.limiter.NoOpRateLimiter;
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
 *   <li>enabled = {@code true} — a {@link RedisRateLimiter} protects the create endpoint;
 *   <li>enabled = {@code false} (default) or unset — the {@link NoOpRateLimiter} admits everything,
 *       so default deployments are unchanged.
 * </ul>
 *
 * <p>The conditions are mutually exclusive so the two implementations never clash on the {@code
 * RateLimiter} bean.
 */
@Configuration
public class RateLimitConfig {

  @Bean
  @ConditionalOnProperty(prefix = "shortener.rate-limiter", value = "enabled", havingValue = "true")
  public RateLimiter redisRateLimiter(
      StringRedisTemplate redis, RateLimitProperties properties, MetricsRecorder metrics) {
    return new RedisRateLimiter(redis, properties.window(), properties.maxRequests(), metrics);
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
}
