package com.javaded78.urlshorter.infra.http;

import java.math.BigInteger;

/**
 * IPv4/IPv6 CIDR membership test for a trusted-proxy allowlist (Task 20.4 hardening).
 *
 * <p>Handles the shapes the configuration accepts: a bare host ({@code 10.0.0.5}, {@code fe80::1}),
 * an IPv4 {@code a.b.c.d/n} block, and an IPv6 {@code prefix/plen} block. Anything else fails
 * closed (the entry never matches) — an unparseable trusted proxy must not silently accept every
 * peer.
 *
 * <p>Membership is a full-width prefix comparison: both addresses are treated as big integers and
 * the leading {@code bits} bits are masked, so a partial final octet is handled uniformly.
 */
final class CidrMatcher {

  private CidrMatcher() {}

  /**
   * @return {@code true} if {@code address} falls inside the {@code cidr} block.
   */
  static boolean match(String cidr, String address) {
    int slash = cidr.indexOf('/');
    if (slash < 0) {
      return cidr.equals(address);
    }
    String prefix = cidr.substring(0, slash);
    String prefixBits = cidr.substring(slash + 1);
    if (prefixBits.isBlank()) {
      return false;
    }
    int bits;
    try {
      bits = Integer.parseInt(prefixBits);
    } catch (NumberFormatException ex) {
      return false;
    }

    BigInteger[] target = toInteger(prefix);
    BigInteger[] candidate = toInteger(address);
    if (target == null || candidate == null) {
      return false;
    }
    BigInteger[] base = target;
    int width = base[1].intValue();
    if (bits < 0 || bits > width) {
      return false;
    }
    BigInteger mask =
        bits == 0
            ? BigInteger.ZERO
            : (BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE)).shiftLeft(width - bits);
    // Compare the masked prefix of both addresses.
    BigInteger targetMasked = base[0].and(mask);
    BigInteger candidateMasked = candidate[0].and(mask);
    return targetMasked.equals(candidateMasked);
  }

  /**
   * @return the address as {@code [value, width]} ({@code width} = bit width of the family), or
   *     {@code null} if the string is not a valid IPv4/IPv6 literal
   */
  private static BigInteger[] toInteger(String address) {
    if (address.contains(":")) {
      // IPv6: 128 bits.
      try {
        java.net.InetAddress inet = java.net.InetAddress.getByName(address);
        byte[] bytes = inet.getAddress();
        if (bytes.length != 16) {
          return null;
        }
        return new BigInteger[] {new BigInteger(1, bytes), BigInteger.valueOf(128)};
      } catch (Exception ex) {
        return null;
      }
    }
    // IPv4: 32 bits.
    String[] octets = address.split("\\.");
    if (octets.length != 4) {
      return null;
    }
    long value = 0;
    for (int i = 0; i < 4; i++) {
      int octet;
      try {
        octet = Integer.parseInt(octets[i]);
      } catch (NumberFormatException ex) {
        return null;
      }
      if (octet < 0 || octet > 255) {
        return null;
      }
      value = (value << 8) | octet;
    }
    return new BigInteger[] {BigInteger.valueOf(value), BigInteger.valueOf(32)};
  }
}
