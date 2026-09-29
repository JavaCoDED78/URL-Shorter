package com.javaded78.urlshorter.infra.redis.cache;

import com.javaded78.urlshorter.domain.cache.CachedLink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CacheTtlPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration CONFIGURED = Duration.ofHours(1);

    @Test
    void neverExpiringLinkUsesConfiguredTtl() {
        Optional<Duration> ttl = CacheTtlPolicy.ttlFor(null, NOW, CONFIGURED);

        assertThat(ttl).contains(CONFIGURED);
    }

    @Test
    void longRemainingLifetimeUsesConfiguredTtl() {
        Optional<Duration> ttl = CacheTtlPolicy.ttlFor(NOW.plus(Duration.ofDays(1)), NOW, CONFIGURED);

        assertThat(ttl).contains(CONFIGURED);
    }

    @Test
    void shortRemainingLifetimeWinsOverConfiguredTtl() {
        Optional<Duration> ttl = CacheTtlPolicy.ttlFor(NOW.plusSeconds(30), NOW, CONFIGURED);

        assertThat(ttl).contains(Duration.ofSeconds(30));
    }

    @Test
    void alreadyExpiredLinkIsNotCached() {
        Optional<Duration> ttl = CacheTtlPolicy.ttlFor(NOW.minusSeconds(1), NOW, CONFIGURED);

        assertThat(ttl).isEmpty();
    }

    @Test
    void expirationAtExactNowIsNotCached() {
        // Consistent with the domain policy: expiresAt <= now -> expired.
        Optional<Duration> ttl = CacheTtlPolicy.ttlFor(NOW, NOW, CONFIGURED);

        assertThat(ttl).isEmpty();
    }
}
