package com.javaded78.urlshorter.domain.service;

/**
 * Signals that a custom alias (or the race-check before insert) collided with an existing code.
 * Maps to HTTP 409.
 */
public class AliasAlreadyExistsException extends ShortenerException {

  public AliasAlreadyExistsException(String code) {
    super("code already taken: " + code);
  }
}
