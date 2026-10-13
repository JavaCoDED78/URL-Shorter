package com.javaded78.urlshorter.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Typed access to {@code shortener.*} configuration (Tasks 4.1-4.4), nested to mirror the YAML
 * structure.
 *
 * <p>Validated on startup: the constraints on the record components are applied by Spring Boot's
 * {@code ConfigurationPropertiesBindingPostProcessor} (this class carries {@code @Validated}), so
 * an out-of-range value — e.g. {@code code.length: 999} or {@code cache.ttl: -1h} — fails fast at
 * context initialization with a readable message instead of surfacing later as a cryptic {@link
 * IllegalArgumentException} deep in the code generator or validator.
 *
 * @see PositiveDuration
 */
@Validated
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
    @Valid Url url,
    @Valid Alias alias,
    @Valid Create create,
    @Valid Cache cache,
    @Valid Code code,
    DataSize maxPayloadSize,
    int maxUriLength) {

  /**
   * @param maxLength maximum accepted original URL length
   * @param allowedSchemes schemes accepted for original URLs
   */
  public record Url(
      @Positive @Max(8192) int maxLength, @NotEmpty @Size(min = 1) List<String> allowedSchemes) {}

  /**
   * @param maxLength maximum custom alias length (fits the {@code code VARCHAR(64)} column)
   * @param reserved aliases rejected because they collide with system routes
   */
  public record Alias(
      @Positive @Max(64) int maxLength, @NotEmpty @Size(min = 1) Set<String> reserved) {}

  /**
   * @param maxCollisionRetries bounded retries when a generated code collides with an existing row
   */
  public record Create(@Positive @Max(20) int maxCollisionRetries) {}

  /**
   * @param ttl Redis TTL for cached links ({@code link:{code}}); for links with {@code expiresAt}
   *     the effective TTL is the minimum of this value and the remaining lifetime
   * @param negativeTtl TTL for the negative cache (a short-lived marker on a code that did not
   *     exist), so a scan of nonexistent codes stops hammering PostgreSQL (Task 21.2). The value
   *     must be short — 30–60s is the trade-off window between shielding the database and missing a
   *     link that was created during the gap.
   * @param expectedCodes the Bloom filter (Task 21.2) is sized for this many codes before it
   *     reaches the configured false-positive rate
   * @param bloomFalsePositiveRate target false-positive rate of the Bloom filter, in (0, 1)
   * @param jitter symmetric TTL jitter about the base cache TTL, in [0, 0.5] (default 0.15, i.e.
   *     ±15%). Spreads out the expiry of a mass of links created together (imports, campaigns) so
   *     they do not all expire in the same second and stampede PostgreSQL; 0 disables jitter
   */
  public record Cache(
      @PositiveDuration java.time.Duration ttl,
      @PositiveDuration java.time.Duration negativeTtl,
      @Positive long expectedCodes,
      double bloomFalsePositiveRate,
      @PositiveOrZero @DecimalMax("0.5") double jitter) {}

  /**
   * @param length number of characters in generated Base62 codes; bounded by {@code [1, 10]} — the
   *     largest length that both encodes into a {@code long} and round-trips back to the exact
   *     value (length 11 would overflow the {@code long} range used by the generator)
   */
  public record Code(@Min(1) @Max(10) int length) {}
}
