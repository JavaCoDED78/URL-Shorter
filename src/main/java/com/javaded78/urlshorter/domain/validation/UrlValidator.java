package com.javaded78.urlshorter.domain.validation;

import com.javaded78.urlshorter.domain.config.UrlConfig;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Validates original URLs (Task 4.2), hardened against SSRF.
 *
 * <p>Uses {@link URI} structural parsing rather than a regex: the scheme must be present and in the
 * allowed list (case-insensitive), a host must exist, and the host must not resolve to an internal
 * address (loopback, link-local, RFC&nbsp;1918/ULA private, or the reserved any/loopback range).
 * Regex alone cannot reliably cover scheme/host rules, IDN, ports and userinfo; {@link InetAddress}
 * resolution is required to catch numeric IPv4 forms (e.g. {@code 2130706433} == {@code 127.0.0.1})
 * that {@link URI} happily accepts.
 *
 * <p><b>SSRF limitation (see ADR-010).</b> Resolution happens once, at creation time. A {@link
 * UnknownHostException} — unreachable or failing DNS — fails <em>open</em>: the URL is accepted
 * rather than blocking a legitimate public host whose DNS is momentarily unreachable. This is
 * deliberate: failing closed on DNS failure would deny creation during a transient outage and turns
 * DNS availability into a denial-of-service vector. It does <em>not</em> defend against DNS
 * rebinding (a host that resolves public at create time and private at redirect time); closing that
 * gap would require re-validating on every redirect, which this stateless service does not do.
 */
@Component
public class UrlValidator {

  /**
   * Bounds concurrent {@link InetAddress} lookups so the JVM DNS resolver cannot saturate. Empirically
   * the JDK resolver deadlocks when many {@code getByName} calls fire at once (the 300-way {@code
   * ConcurrencyIT} previously stalled), and a hard ceiling around 8 concurrent lookups keeps resolution
   * fast. The count is deliberately below the Hikari maximum pool size (10) so at most 8 connections
   * are ever held during validation.
   */
  private static final int DNS_SEMAPHORE_PERMITS = 8;
  private static final Semaphore dnsPermits = new Semaphore(DNS_SEMAPHORE_PERMITS);

  private final int maxLength;
  private final Set<String> allowedSchemes;

  public UrlValidator(UrlConfig config) {
    this.maxLength = config.maxLength();
    this.allowedSchemes =
        config.allowedSchemes().stream()
            .map(scheme -> scheme.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
  }

  /**
   * @throws IllegalArgumentException if the URL is null, blank, too long, unparsable, has a
   *     disallowed scheme, has no host, or resolves to an internal address
   */
  public void validate(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("URL must not be null or blank");
    }
    if (url.length() > maxLength) {
      throw new IllegalArgumentException(
          "URL length " + url.length() + " exceeds the maximum of " + maxLength);
    }
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("URL is not syntactically valid: " + ex.getReason());
    }
    String scheme = uri.getScheme();
    if (scheme == null || !allowedSchemes.contains(scheme.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException(
          "URL scheme must be one of " + allowedSchemes + ": " + url);
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalArgumentException("URL must contain a host: " + url);
    }
    rejectInternalHost(uri, url);
  }

  /**
   * Resolves the host and rejects it when it is an internal address.
   * defence: a {@link UnknownHostException} (unreachable or failing DNS) fails <em>open</em> — the
   * URL is accepted — rather than blocking legitimate creation during a transient outage.
   */
  private void rejectInternalHost(URI uri, String url) {
    InetAddress address;
    try {
      dnsPermits.acquire();
      address = InetAddress.getByName(uri.getHost());
    } catch (UnknownHostException ignored) {
      // Cannot prove the host is public (no DNS or a transient outage): fail open.
      return;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalArgumentException("URL host lookup interrupted: " + url);
    } finally {
      dnsPermits.release();
    }
    if (isInternalAddress(address)) {
      throw new IllegalArgumentException("URL must not point at an internal address: " + url);
    }
  }

  /**
   * True for loopback, link-local, any-local and multicast addresses (covered by the JDK) plus the
   * RFC&nbsp;1918/ULA private ranges and the IPv4 broadcast/reserved range (which {@link
   * InetAddress} has no predicate for on this runtime — {@code InetAddress#isPrivate} only exists
   * from Java&nbsp;25).
   */
  private static boolean isInternalAddress(InetAddress address) {
    if (address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isAnyLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    byte[] octets = address.getAddress();
    if (octets.length == 4) {
      int first = octets[0] & 0xff;
      int second = octets[1] & 0xff;
      if (first == 10) {
        return true; // 10.0.0.0/8
      }
      if (first == 172 && second >= 16 && second <= 31) {
        return true; // 172.16.0.0/12
      }
      if (first == 192 && second == 168) {
        return true; // 192.168.0.0/16
      }
      if (first >= 240) {
        return true; // 240.0.0.0/4 reserved, incl. 255.255.255.255 broadcast
      }
    }
    if (octets.length == 16 && (octets[0] & 0xff) == 0xfc) {
      return true; // IPv6 unique-local fc00::/7
    }
    return false;
  }
}
