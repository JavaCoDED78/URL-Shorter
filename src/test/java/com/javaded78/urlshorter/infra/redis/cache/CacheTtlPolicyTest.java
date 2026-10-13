package com.javaded78.urlshorter.infra.redis.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CacheTtlPolicyTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Duration CONFIGURED = Duration.ofHours(1);

  // Jitter disabled: the base policy is deterministic and unchanged from the jitter-free version.
  private final CacheTtlPolicy noJitter = new CacheTtlPolicy(0d);

  @Test
  void neverExpiringLinkUsesConfiguredTtl() {
    Optional<Duration> ttl = noJitter.ttlFor(null, NOW, CONFIGURED);

    assertThat(ttl).contains(CONFIGURED);
  }

  @Test
  void longRemainingLifetimeUsesConfiguredTtl() {
    Optional<Duration> ttl = noJitter.ttlFor(NOW.plus(Duration.ofDays(1)), NOW, CONFIGURED);

    assertThat(ttl).contains(CONFIGURED);
  }

  @Test
  void shortRemainingLifetimeWinsOverConfiguredTtl() {
    Optional<Duration> ttl = noJitter.ttlFor(NOW.plusSeconds(30), NOW, CONFIGURED);

    assertThat(ttl).contains(Duration.ofSeconds(30));
  }

  @Test
  void alreadyExpiredLinkIsNotCached() {
    Optional<Duration> ttl = noJitter.ttlFor(NOW.minusSeconds(1), NOW, CONFIGURED);

    assertThat(ttl).isEmpty();
  }

  @Test
  void expirationAtExactNowIsNotCached() {
    // Consistent with the domain policy: expiresAt <= now -> expired.
    Optional<Duration> ttl = noJitter.ttlFor(NOW, NOW, CONFIGURED);

    assertThat(ttl).isEmpty();
  }

  @Test
  void jitterDisabledReturnsExactBaseTtl() {
    Optional<Duration> ttl = new CacheTtlPolicy(0d).ttlFor(null, NOW, CONFIGURED);

    assertThat(ttl).contains(CONFIGURED);
  }

  @Test
  void jitterKeepsTtlWithinBounds() {
    // ±15% about the base: a never-expiring link always lands in [0.85h, 1.15h].
    CacheTtlPolicy policy = new CacheTtlPolicy(0.15d);
    for (int i = 0; i < 10_000; i++) {
      Optional<Duration> ttl = policy.ttlFor(null, NOW, CONFIGURED);

      assertThat(ttl)
          .isPresent()
          .satisfies(
              v ->
                  assertThat(v.get())
                      .isBetween(
                          CONFIGURED.multipliedBy(85).dividedBy(100),
                          CONFIGURED.multipliedBy(115).dividedBy(100)));
    }
  }

  @Test
  void jitterClampedToHalf() {
    // A pathological request is clamped to the ±50% ceiling, so a TTL never collapses toward zero.
    CacheTtlPolicy policy = new CacheTtlPolicy(0.99d);
    for (int i = 0; i < 10_000; i++) {
      Optional<Duration> ttl = policy.ttlFor(null, NOW, CONFIGURED);

      assertThat(ttl)
          .isPresent()
          .satisfies(v -> assertThat(v.get()).isGreaterThanOrEqualTo(CONFIGURED.dividedBy(2)));
    }
  }

  @Test
  void jitterAppliesToRemainingLifetimeToo() {
    // A link with 30s left is jittered about 30s, not the configured 1h.
    CacheTtlPolicy policy = new CacheTtlPolicy(0.15d);
    Duration base = Duration.ofSeconds(30);
    for (int i = 0; i < 10_000; i++) {
      Optional<Duration> ttl = policy.ttlFor(NOW.plusSeconds(30), NOW, CONFIGURED);

      assertThat(ttl)
          .isPresent()
          .satisfies(
              v ->
                  assertThat(v.get())
                      .isBetween(
                          base.multipliedBy(85).dividedBy(100),
                          base.multipliedBy(115).dividedBy(100)));
    }
  }
}
