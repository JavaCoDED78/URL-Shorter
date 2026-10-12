package com.javaded78.urlshorter.infra.http;

import com.javaded78.urlshorter.domain.service.CreateRateLimitSource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * {@link CreateRateLimitSource} that scopes the create limiter by the client's IP (Task 20.4
 * hardening).
 *
 * <p>The key is the caller's IP. When {@code X-Forwarded-For} is present and the immediate peer is
 * a trusted proxy, the key is the <em>first hop</em> of that chain — the real client behind the
 * proxy stack. Otherwise the header is untrusted (it is attacker-controlled) and the key falls back
 * to {@code HttpServletRequest#getRemoteAddr()}, so a forged {@code XFF} header can only masquerade
 * as the local proxy's address.
 *
 * <p>The list of trusted proxies is an explicit allowlist of {@code ipv4}/{@code ipv6}/{@code cidr}
 * values (e.g. {@code 10.0.0.0/8}), bound from {@code shortener.rate-limiter.trusted-proxies}. An
 * empty list means "no trusted proxy", which forces {@code remoteAddr} for every request.
 *
 * <p>The resolver is a pure function of {@code (XFF, remoteAddr)} — the request is only read
 * through the {@link Supplier}, so the class stays unit-testable without a servlet container.
 */
public class CreateIpRateLimitSource implements CreateRateLimitSource {

  private final String[] trustedProxies;
  private final Supplier<HttpServletRequest> request;

  public CreateIpRateLimitSource(String[] trustedProxies) {
    this(
        trustedProxies,
        () ->
            RequestContextHolder.getRequestAttributes() instanceof HttpServletRequest req
                ? req
                : null);
  }

  CreateIpRateLimitSource(String[] trustedProxies, Supplier<HttpServletRequest> request) {
    this.trustedProxies = trustedProxies.clone();
    this.request = request;
  }

  @Override
  public String resolveClientKey() {
    HttpServletRequest httpRequest = request.get();
    String xff = httpRequest != null ? httpRequest.getHeader("X-Forwarded-For") : null;
    String remoteAddr = httpRequest != null ? httpRequest.getRemoteAddr() : "unknown";
    return resolve(xff, remoteAddr);
  }

  /**
   * Pure resolver: derive the per-client key from the {@code X-Forwarded-For} header and the peer's
   * remote address.
   *
   * @param xffHeader the raw {@code X-Forwarded-For} header (may be {@code null})
   * @param remoteAddr the connection peer's address; also the trusted-proxy identity
   * @return the client key to scope the limiter by
   */
  String resolve(@Nullable String xffHeader, String remoteAddr) {
    if (StringUtils.hasText(xffHeader) && isTrusted(remoteAddr)) {
      String firstHop = firstHopOfXff(xffHeader);
      if (firstHop != null) {
        return firstHop;
      }
    }
    return remoteAddr;
  }

  private boolean isTrusted(String remoteAddr) {
    if (trustedProxies.length == 0) {
      return false;
    }
    return Arrays.stream(trustedProxies).anyMatch(entry -> CidrMatcher.match(entry, remoteAddr));
  }

  private static String firstHopOfXff(String xff) {
    // XFF is "client, proxy1, proxy2"; the first entry is the client.
    String first = xff.split(",")[0].trim();
    return first.isEmpty() ? null : first;
  }
}
