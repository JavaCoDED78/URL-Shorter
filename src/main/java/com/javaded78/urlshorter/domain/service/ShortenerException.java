package com.javaded78.urlshorter.domain.service;

/**
 * Base type for expected, client-facing application failures (Task 11.1).
 *
 * <p>The hierarchy is intentionally small — one class per stable error
 * category, not per minor condition:
 *
 * <pre>
 * ShortenerException
 * ├── LinkNotFoundException      (404)
 * ├── LinkExpiredException       (410)
 * └── AliasAlreadyExistsException (409)
 * </pre>
 *
 * <p>Request validation failures (bad URL, bad alias, bad expiration) stay
 * plain {@link IllegalArgumentException} — they are all one 400 category and
 * do not deserve dedicated classes.
 */
public abstract class ShortenerException extends RuntimeException {

    protected ShortenerException(String message) {
        super(message);
    }
}
