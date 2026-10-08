package com.javaded78.urlshorter.api.error;

import com.javaded78.urlshorter.config.PayloadTooLargeException;
import com.javaded78.urlshorter.domain.service.AliasAlreadyExistsException;
import com.javaded78.urlshorter.domain.service.LinkExpiredException;
import com.javaded78.urlshorter.domain.service.LinkNotFoundException;
import com.javaded78.urlshorter.domain.service.RateLimitExceededException;
import com.javaded78.urlshorter.domain.service.ShortenerException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps application exceptions to RFC 9457 {@link ProblemDetail} responses.
 *
 * <p>Stable, client-facing categories (Task 11.2): 400 invalid request, 404 not found, 409
 * conflict, 410 gone, 500 internal error. Every response carries the same keys — {@code type},
 * {@code title}, {@code status}, {@code detail}, {@code instance}. The {@code type} field is a
 * machine-readable URI identifying each error class (so clients can branch on the error kind
 * without parsing the human-readable {@code title}), while {@code title} stays stable for human
 * consumption. The unexpected-failure fallback (Task 11.4) logs the cause server-side but never
 * exposes stack traces, SQL, connection URLs or Redis internals to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request validation failed");
    problem.setType(ErrorType.VALIDATION.uri());
    problem.setTitle(ErrorType.VALIDATION.title());
    String fields =
        ex.getBindingResult().getFieldErrors().stream()
            .map(GlobalExceptionHandler::formatFieldError)
            .collect(Collectors.joining("; "));
    problem.setProperty("errors", fields);
    return problem;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    problem.setType(ErrorType.INVALID_REQUEST.uri());
    problem.setTitle(ErrorType.INVALID_REQUEST.title());
    return problem;
  }

  /**
   * Maps the four {@link ShortenerException} subtypes — 404, 410, 409, 429 — to {@link ProblemDetail}.
   *
   * <p>{@link ShortenerException} is {@code sealed} with exactly four permitted subtypes, so this
   * {@code switch} is <b>exhaustive by construction</b>: every branch covers one stable error
   * category, and the compiler forbids a subtype reaching the API without a mapping. Adding a new
   * subtype (or dropping one) is a compile-time error, not a silent fallback to 500 — which keeps
   * the {@code ErrorType} contract stable.
   *
   * <p>The rate-limit case carries an extra {@code retryAfter} property (Task 20.4); it is distinct
   * from {@link DataAccessException} (503) and the unexpected-failure fallback (500).
   */
  @ExceptionHandler(ShortenerException.class)
  public ProblemDetail handleShortenerException(ShortenerException ex) {
    return switch (ex) {
      case LinkNotFoundException e -> mapped(ErrorType.LINK_NOT_FOUND, e);
      case LinkExpiredException e -> mapped(ErrorType.LINK_EXPIRED, e);
      case AliasAlreadyExistsException e -> mapped(ErrorType.CODE_ALREADY_TAKEN, e);
      case RateLimitExceededException e -> {
        ProblemDetail problem = mapped(ErrorType.RATE_LIMITED, e);
        problem.setProperty("retryAfter", 1);
        yield problem;
      }
    };
  }

  private static ProblemDetail mapped(ErrorType type, ShortenerException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), ex.getMessage());
    problem.setType(type.uri());
    problem.setTitle(type.title());
    return problem;
  }

  /**
   * Database unavailable (Tasks 18.1, 18.2): a data-access failure means a dependency is down, not
   * a bug — 503 tells the client to retry later, distinct from both 409 (business conflict) and 500
   * (unexpected). The cause is logged server-side only; the connection-acquisition timeout is
   * pinned at 5 s in application.yaml so the request cannot hang.
   */
  @ExceptionHandler(DataAccessException.class)
  public ProblemDetail handleDataAccessException(DataAccessException ex) {
    log.error("data access failure while handling request", ex);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE, "database temporarily unavailable");
    problem.setType(ErrorType.DATABASE_UNAVAILABLE.uri());
    problem.setTitle(ErrorType.DATABASE_UNAVAILABLE.title());
    return problem;
  }

  /**
   * Transaction-begin failure with the database unreachable (found in the Sprint 28 live review):
   * when the container is down, the JPA transaction manager cannot even open an EntityManager, so
   * the failure surfaces as {@link CannotCreateTransactionException} — which is NOT a {@code
   * DataAccessException} and would otherwise leak through as a misleading 500. This is the same
   * "database temporarily unavailable" contract as above; rollback/commit failures after a running
   * transaction are a different class and still fall to 500.
   */
  @ExceptionHandler(CannotCreateTransactionException.class)
  public ProblemDetail handleCannotCreateTransaction(CannotCreateTransactionException ex) {
    log.error("transaction begin failure while handling request", ex);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE, "database temporarily unavailable");
    problem.setType(ErrorType.DATABASE_UNAVAILABLE.uri());
    problem.setTitle(ErrorType.DATABASE_UNAVAILABLE.title());
    return problem;
  }

  /**
   * Body over the payload limit (Task 20.1): thrown by the bounded stream for chunked bodies;
   * usually arrives wrapped by the message converter.
   */
  @ExceptionHandler(PayloadTooLargeException.class)
  public ProblemDetail handlePayloadTooLarge(PayloadTooLargeException ex) {
    return payloadTooLarge(ex.getMessage());
  }

  /**
   * Unreadable body: 400 for syntactically broken JSON, 413 when the cause chain shows the bounded
   * stream hit the payload limit (chunked oversized bodies, Task 20.1).
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof PayloadTooLargeException tooLarge) {
        return payloadTooLarge(tooLarge.getMessage());
      }
    }
    log.warn("unreadable request body: {}", ex.toString());
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request body is not readable");
    problem.setType(ErrorType.INVALID_REQUEST.uri());
    problem.setTitle(ErrorType.INVALID_REQUEST.title());
    return problem;
  }

  private static ProblemDetail payloadTooLarge(String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, detail);
    problem.setType(ErrorType.PAYLOAD_TOO_LARGE.uri());
    problem.setTitle(ErrorType.PAYLOAD_TOO_LARGE.title());
    return problem;
  }

  /**
   * Unmapped paths (e.g. retired unversioned API routes) must be a plain 404 — without this, the
   * generic fallback below would turn them into a misleading 500.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ProblemDetail handleNoResource(NoResourceFoundException ex) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "resource not found");
    problem.setType(ErrorType.RESOURCE_NOT_FOUND.uri());
    problem.setTitle(ErrorType.RESOURCE_NOT_FOUND.title());
    return problem;
  }

  /**
   * Fallback for everything not covered above — infrastructure failures (PostgreSQL, Redis),
   * unexpected runtime exceptions. The cause is logged server-side only (Task 11.4); the client
   * gets a generic ProblemDetail.
   */
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex) {
    log.error("unexpected error while handling request", ex);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected error");
    problem.setType(ErrorType.INTERNAL_ERROR.uri());
    problem.setTitle(ErrorType.INTERNAL_ERROR.title());
    return problem;
  }

  private static String formatFieldError(FieldError error) {
    return error.getField() + ": " + error.getDefaultMessage();
  }
}
