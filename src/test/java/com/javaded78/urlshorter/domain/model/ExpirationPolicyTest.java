package com.javaded78.urlshorter.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExpirationPolicyTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  // Policy (Task 5.2): timestamps are UTC Instants — the single source of truth for the
  // "expired" decision shared by the model and the cached value, re-checked on every read.

  @Test
  void nullExpirationNeverExpires() {
    assertThat(ExpirationPolicy.isExpired(null, NOW)).isFalse();
    assertThat(ExpirationPolicy.isExpired(NOW.plusSeconds(10_000_000), NOW)).isFalse();
  }

  @Test
  void futureExpirationIsActive() {
    assertThat(ExpirationPolicy.isExpired(NOW.plusSeconds(60), NOW)).isFalse();
  }

  @Test
  void pastExpirationIsExpired() {
    assertThat(ExpirationPolicy.isExpired(NOW.minusSeconds(1), NOW)).isTrue();
  }

  @Test
  void expirationAtExactNowIsExpired() {
    // Policy: expiresAt <= now -> expired. The boundary is deterministic
    // only in the unit test; over HTTP "now" always advances.
    assertThat(ExpirationPolicy.isExpired(NOW, NOW)).isTrue();
  }
}
