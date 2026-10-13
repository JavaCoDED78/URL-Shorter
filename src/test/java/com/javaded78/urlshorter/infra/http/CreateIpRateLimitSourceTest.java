package com.javaded78.urlshorter.infra.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Key resolution for the create rate limiter (Task 20.4 hardening): the key is the caller's IP,
 * taken from the first hop of {@code X-Forwarded-For} when the proxy is trusted, otherwise the
 * direct remote address. A trusted proxy must therefore <em>not</em> let a caller forge its key,
 * and an absent header must fall back to {@code remoteAddr} — never {@code "unknown"}.
 */
class CreateIpRateLimitSourceTest {

  private static final String TRUSTED = "10.0.0.5";

  private final CreateIpRateLimitSource source =
      new CreateIpRateLimitSource(new String[] {TRUSTED});

  @Test
  void absentXffFallsBackToRemoteAddr() {
    assertThat(source.resolve(null, "198.51.100.7")).isEqualTo("198.51.100.7");
  }

  @Test
  void blankXffFallsBackToRemoteAddr() {
    assertThat(source.resolve("  ", "198.51.100.7")).isEqualTo("198.51.100.7");
  }

  @Test
  void trustedProxyUsesFirstHopOfXff() {
    assertThat(source.resolve("203.0.113.9, 10.0.0.5", TRUSTED)).isEqualTo("203.0.113.9");
  }

  @Test
  void singleEntryXffFromTrustedProxyIsTheClient() {
    assertThat(source.resolve("203.0.113.9", TRUSTED)).isEqualTo("203.0.113.9");
  }

  @Test
  void untrustedProxyHeaderIsIgnored() {
    // The peer is not a trusted proxy, so the forged XFF is discarded and remoteAddr wins.
    assertThat(source.resolve("203.0.113.9", "127.0.0.1")).isEqualTo("127.0.0.1");
  }

  @Test
  void emptyTrustedListForcesRemoteAddr() {
    CreateIpRateLimitSource source = new CreateIpRateLimitSource(new String[] {});

    assertThat(source.resolve("203.0.113.9", "10.0.0.5")).isEqualTo("10.0.0.5");
  }

  @Test
  void cidrBlockMatchesTrustedPeer() {
    CreateIpRateLimitSource source = new CreateIpRateLimitSource(new String[] {"10.0.0.0/8"});

    assertThat(source.resolve("203.0.113.9", "10.1.2.3")).isEqualTo("203.0.113.9");
  }
}
