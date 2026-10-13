package com.javaded78.urlshorter.domain.service;

/**
 * Port for resolving the per-client identity the create rate limiter is scoped by (Task 20.4
 * hardening).
 *
 * <p>The shipped {@code CreateIpRateLimitSource} derives the key from the caller's IP — the first
 * hop of the (optionally trusted) {@code X-Forwarded-For} chain, falling back to {@code
 * HttpServletRequest#getRemoteAddr()} when the header is absent. Implementations live in {@code
 * infra}; the domain only depends on this port so it never touches {@code HttpServletRequest}
 * directly.
 */
public interface CreateRateLimitSource {

  /**
   * @return the per-client key the create limiter must scope by for this request (e.g. a client IP
   *     or, behind a trusted proxy, the first hop of {@code X-Forwarded-For}); never {@code null}
   */
  String resolveClientKey();
}
