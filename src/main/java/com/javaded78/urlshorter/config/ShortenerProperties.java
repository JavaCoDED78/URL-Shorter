package com.javaded78.urlshorter.config;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed access to {@code shortener.*} configuration (Tasks 4.1-4.4), nested to mirror the YAML
 * structure.
 */
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
    Url url,
    Alias alias,
    Create create,
    Cache cache,
    Code code,
    org.springframework.util.unit.DataSize maxPayloadSize,
    int maxUriLength) {

  /**
   * @param maxLength maximum accepted original URL length
   * @param allowedSchemes schemes accepted for original URLs
   */
  public record Url(int maxLength, List<String> allowedSchemes) {}

  /**
   * @param maxLength maximum custom alias length (fits the {@code code VARCHAR(64)} column)
   * @param reserved aliases rejected because they collide with system routes
   */
  public record Alias(int maxLength, Set<String> reserved) {}

  /**
   * @param maxCollisionRetries bounded retries when a generated code collides with an existing row
   */
  public record Create(int maxCollisionRetries) {}

  /**
   * @param ttl Redis TTL for cached links ({@code link:{code}}); for links with {@code expiresAt}
   *     the effective TTL is the minimum of this value and the remaining lifetime
   */
  public record Cache(java.time.Duration ttl) {}

  /**
   * @param length number of characters in generated Base62 codes; bounded by {@code [1, 11]} — a
   *     {@code long} holds at most 11 base62 digits
   */
  public record Code(int length) {}
}
