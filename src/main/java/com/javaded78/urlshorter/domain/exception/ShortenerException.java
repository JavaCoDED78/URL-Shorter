package com.javaded78.urlshorter.domain.exception;

/**
 * Base type for expected, client-facing application failures.
 *
 * <p>The hierarchy is intentionally small — one class per stable error category, not per minor
 * condition:
 *
 * <pre>
 * ShortenerException
 * ├── LinkNotFoundException      (404)
 * ├── LinkExpiredException       (410)
 * └── AliasAlreadyExistsException (409)
 * </pre>
 *
 * <p>Request validation failures (bad URL, bad alias, bad expiration) stay plain {@link
 * IllegalArgumentException} — they are all one 400 category and do not deserve dedicated classes.
 *
 * <p>{@code sealed}: the {@code switch} in {@link
 * com.javaded78.urlshorter.api.error.GlobalExceptionHandler#handleShortenerException} is exhaustive
 * by construction, so adding a subtype without a mapping is a compile error, not a silent 500.
 */
public abstract sealed class ShortenerException extends RuntimeException
    permits LinkNotFoundException,
        LinkExpiredException,
        AliasAlreadyExistsException,
        RateLimitExceededException {

  protected ShortenerException(String message) {
    super(message);
  }
}
