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

  @Bean
  UrlConfig urlConfig(ShortenerProperties properties) {
    return new UrlConfig(properties.url().maxLength(), properties.url().allowedSchemes());
  }

  @Bean
  AliasConfig aliasConfig(ShortenerProperties properties) {
    return new AliasConfig(properties.alias().maxLength(), properties.alias().reserved());
  }
}
