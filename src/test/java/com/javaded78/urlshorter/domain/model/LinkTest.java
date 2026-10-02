package com.javaded78.urlshorter.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LinkTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void holdsBusinessData() {
    Instant expiresAt = Instant.parse("2026-02-01T00:00:00Z");
    Link link = new Link("abc1234", "https://example.com", expiresAt, 42, NOW);

    assertThat(link.code()).isEqualTo("abc1234");
    assertThat(link.originalUrl()).isEqualTo("https://example.com");
    assertThat(link.expiresAt()).isEqualTo(expiresAt);
    assertThat(link.clickCount()).isEqualTo(42);
    assertThat(link.createdAt()).isEqualTo(NOW);
  }

  @Test
  void supportsNeverExpiringLinks() {
    Link link = new Link("abc1234", "https://example.com", null, 0, NOW);

    assertThat(link.expiresAt()).isNull();
  }

  @Test
  void rejectsNullCode() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Link(null, "https://example.com", null, 0, NOW));
  }

  @Test
  void rejectsBlankCode() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Link("  ", "https://example.com", null, 0, NOW));
  }

  @Test
  void rejectsNullOriginalUrl() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Link("abc1234", null, null, 0, NOW));
  }

  @Test
  void rejectsBlankOriginalUrl() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Link("abc1234", " ", null, 0, NOW));
  }

  @Test
  void rejectsNegativeClickCount() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Link("abc1234", "https://example.com", null, -1, NOW));
  }

  @Test
  void rejectsNullCreatedAt() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Link("abc1234", "https://example.com", null, 0, null));
  }

  // --- expiration policy (Task 5.2): timestamps are UTC Instants ---

  @Test
  void nullExpirationNeverExpires() {
    Link link = new Link("abc1234", "https://example.com", null, 0, NOW);

    assertThat(link.isExpired(NOW)).isFalse();
    assertThat(link.isExpired(NOW.plusSeconds(10_000_000))).isFalse();
  }

  @Test
  void futureExpirationIsActive() {
    Link link = new Link("abc1234", "https://example.com", NOW.plusSeconds(60), 0, NOW);

    assertThat(link.isExpired(NOW)).isFalse();
  }

  @Test
  void pastExpirationIsExpired() {
    Link link = new Link("abc1234", "https://example.com", NOW.minusSeconds(1), 0, NOW);

    assertThat(link.isExpired(NOW)).isTrue();
  }

  @Test
  void expirationAtExactNowIsExpired() {
    // Policy: expiresAt <= now -> expired. The boundary is deterministic
    // only in the unit test; over HTTP "now" always advances.
    Link link = new Link("abc1234", "https://example.com", NOW, 0, NOW);

    assertThat(link.isExpired(NOW)).isTrue();
  }
}
