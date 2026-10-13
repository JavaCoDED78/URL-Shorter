package com.javaded78.urlshorter.infra.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** IPv4/IPv6 CIDR membership for the trusted-proxy allowlist (ADR-009). */
class CidrMatcherTest {

  @Test
  void hostMatchesOnlyItself() {
    assertThat(CidrMatcher.match("10.0.0.5", "10.0.0.5")).isTrue();
    assertThat(CidrMatcher.match("10.0.0.5", "10.0.0.6")).isFalse();
  }

  @Test
  void ipv4BlockIncludesMembersOutsideTheLastOctet() {
    assertThat(CidrMatcher.match("10.0.0.0/8", "10.1.2.3")).isTrue();
    assertThat(CidrMatcher.match("10.0.0.0/8", "11.0.0.1")).isFalse();
  }

  @Test
  void ipv4PartialMaskHonoursTrailingBits() {
    assertThat(CidrMatcher.match("192.168.1.0/24", "192.168.1.200")).isTrue();
    assertThat(CidrMatcher.match("192.168.1.0/24", "192.168.2.1")).isFalse();
    assertThat(CidrMatcher.match("192.168.1.0/26", "192.168.1.63")).isTrue();
    assertThat(CidrMatcher.match("192.168.1.0/26", "192.168.1.64")).isFalse();
  }

  @Test
  void ipv6BlockMembers() {
    assertThat(CidrMatcher.match("fe80::/10", "fe80::1")).isTrue();
    assertThat(CidrMatcher.match("fe80::/10", "fe81::1")).isTrue();
    assertThat(CidrMatcher.match("fe80::/16", "fe81::1")).isFalse();
  }

  @Test
  void invalidEntriesFailClosed() {
    assertThat(CidrMatcher.match("10.0.0.0/33", "10.0.0.1")).isFalse();
    assertThat(CidrMatcher.match("10.0.0.0", "10.0.0.1")).isFalse();
    assertThat(CidrMatcher.match("not-an-ip", "10.0.0.1")).isFalse();
    assertThat(CidrMatcher.match("10.0.0.0/8", "999.0.0.1")).isFalse();
  }
}
