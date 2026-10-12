package com.javaded78.urlshorter.domain.exception;

/**
 * Unique-code collision raised by the persistence {@code save} operation.
 *
 * <p>The adapter-boundary translation of a framework unique-constraint violation
 * ({@code org.springframework.dao.DataIntegrityViolationException}): {@code JpaLinkRepository}
 * catches the framework exception and re-throws this domain type, so the {@link
 * com.javaded78.urlshorter.domain.service.WriteLinkService} layer reasons only about domain
 * failures and never imports Spring.
 *
 * <p>Deliberately <b>not</b> part of the sealed {@link ShortenerException} client-facing catalog:
 * it is a port-level collision signal that the write service translates into a client-facing
 * exception ({@link AliasAlreadyExistsException} for an alias; a bounded-retry {@link
 * IllegalStateException} for a generated code) before it can escape.
 *
 * @see com.javaded78.urlshorter.infra.persistence.JpaLinkRepository#save(com.javaded78.urlshorter.domain.model.Link)
 */
public final class DuplicateCodeException extends RuntimeException {

  public DuplicateCodeException(String code) {
    super("unique code collision: " + code);
  }
}
