package com.javaded78.urlshorter.config;

import com.javaded78.urlshorter.domain.config.AliasConfig;
import com.javaded78.urlshorter.domain.config.UrlConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges the bound {@code shortener.*} properties into the pure config records consumed by the
 * domain validation ports.
 *
 * <p>{@link UrlValidator} and {@link com.javaded78.urlshorter.domain.validation.AliasValidator}
 * take {@link UrlConfig} / {@link AliasConfig} respectively instead of Spring's {@link
 * ShortenerProperties} (a {@code @ConfigurationProperties} type), so the domain layer never imports
 * a Spring config type. The values are still validated by the constraints on {@link
 * ShortenerProperties} at startup, so an out-of-range value fails fast on context init.
 */
@Configuration
public class ValidationConfig {

  /**
   * Default ceiling on concurrent DNS lookups during URL validation (see {@link
   * com.javaded78.urlshorter.domain.validation.UrlValidator}). Lives here — in the Spring config
   * layer — rather than in the pure {@link com.javaded78.urlshorter.domain.config.UrlConfig}
   * record, so the domain record stays a plain data holder; the value is supplied to the validator
   * through {@link com.javaded78.urlshorter.domain.config.UrlConfig#dnsSemaphorePermits}.
   */
  static final int DEFAULT_DNS_SEMAPHORE_PERMITS = 8;

  @Bean
  UrlConfig urlConfig(ShortenerProperties properties) {
    return new UrlConfig(
        properties.url().maxLength(),
        properties.url().allowedSchemes(),
        DEFAULT_DNS_SEMAPHORE_PERMITS);
  }

  @Bean
  AliasConfig aliasConfig(ShortenerProperties properties) {
    return new AliasConfig(properties.alias().maxLength(), properties.alias().reserved());
  }
}
