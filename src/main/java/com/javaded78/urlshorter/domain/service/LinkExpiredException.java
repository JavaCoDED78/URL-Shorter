package com.javaded78.urlshorter.domain.service;

/** Signals that a link exists but its {@code expiresAt} has passed. Maps to HTTP 410 Gone. */
public class LinkExpiredException extends ShortenerException {

  public LinkExpiredException(String code) {
    super("link expired: " + code);
  }
}
