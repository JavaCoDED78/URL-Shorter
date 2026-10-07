package com.javaded78.urlshorter.domain.service;

/** Signals that a rate limit was exceeded. Maps to HTTP 429 Too Many Requests. */
public class RateLimitExceededException extends ShortenerException {

  public RateLimitExceededException() {
    super("rate limit exceeded");
  }
}
