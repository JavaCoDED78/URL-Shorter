package com.javaded78.urlshorter.domain.exception;

/** Signals that a rate limit was exceeded. Maps to HTTP 429 Too Many Requests. */
public final class RateLimitExceededException extends ShortenerException {

  public RateLimitExceededException() {
    super("rate limit exceeded");
  }
}
