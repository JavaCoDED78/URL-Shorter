package com.javaded78.urlshorter.infra.redis.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.config.ShortenerProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CodeBloomFilter}. The Bloom guards the redirect path: a code that was
 * created must resolve as present on the redirect path regardless of the case the caller uses, so
 * generated mixed-case Base62 codes are not wrongly treated as absent (the code column is unique
 * case-insensitively, and {@link CodeBloomFilter#contains(String)} lowercases before querying).
 */
class CodeBloomFilterTest {

  private CodeBloomFilter bloom;

  @BeforeEach
  void setUp() {
    ShortenerProperties.Cache cache =
        new ShortenerProperties.Cache(
            Duration.ofHours(1), Duration.ofMinutes(30), 5_000L, 0.01, 0.15);
    // Only properties.cache() is read by the constructor; the rest stay null.
    ShortenerProperties properties =
        new ShortenerProperties(null, null, null, cache, null, null, null, 0);
    bloom = new CodeBloomFilter(properties);
  }

  @Test
  void markThenContainsIsPresent() {
    bloom.markExists("eqAYMsH");

    assertThat(bloom.contains("eqAYMsH")).as("the created code is present").isTrue();
  }

  @Test
  void membershipIsCaseInsensitive() {
    bloom.markExists("eqAYMsH");

    assertThat(bloom.contains("eqaymsh")).as("lowercase query must hit").isTrue();
    assertThat(bloom.contains("EQAYMSH")).as("uppercase query must hit").isTrue();
  }

  @Test
  void absentCodeStaysAbsent() {
    assertThat(bloom.contains("never-created")).as("an unknown code is absent").isFalse();
    assertThat(bloom.contains("Never-Created"))
        .as("an unknown code stays absent regardless of case")
        .isFalse();
  }

  @Test
  void nullAndBlankAreIgnored() {
    bloom.markExists(null);
    bloom.markExists("");
    bloom.markExists("   ");

    assertThat(bloom.contains("never-created")).isFalse();
  }
}
