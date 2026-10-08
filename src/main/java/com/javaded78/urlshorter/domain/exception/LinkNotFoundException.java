package com.javaded78.urlshorter.domain.exception;

/** Signals that a link with the requested code does not exist. Maps to HTTP 404. */
public final class LinkNotFoundException extends ShortenerException {

  public LinkNotFoundException(String code) {
    super("link not found: " + code);
  }
}
