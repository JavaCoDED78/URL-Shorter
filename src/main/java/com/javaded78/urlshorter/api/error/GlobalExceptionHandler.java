package com.javaded78.urlshorter.api.error;

import java.net.URI;
import java.util.stream.Collectors;

import com.javaded78.urlshorter.domain.service.AliasAlreadyExistsException;
import com.javaded78.urlshorter.domain.service.LinkExpiredException;
import com.javaded78.urlshorter.domain.service.LinkNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps application exceptions to RFC 9457 {@link ProblemDetail} responses.
 *
 * <p>Stable, client-facing categories (Task 11.2): 400 invalid request,
 * 404 not found, 409 conflict, 410 gone, 500 internal error. Every response
 * carries the same keys — {@code type}, {@code title}, {@code status},
 * {@code detail}, {@code instance}. The unexpected-failure fallback
 * (Task 11.4) logs the cause server-side but never exposes stack traces,
 * SQL, connection URLs or Redis internals to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "request validation failed");
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Invalid request");
        String fields = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        problem.setProperty("errors", fields);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(AliasAlreadyExistsException.class)
    public ProblemDetail handleAliasConflict(AliasAlreadyExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Code already taken");
        return problem;
    }

    @ExceptionHandler(LinkNotFoundException.class)
    public ProblemDetail handleNotFound(LinkNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Link not found");
        return problem;
    }

    @ExceptionHandler(LinkExpiredException.class)
    public ProblemDetail handleExpired(LinkExpiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, ex.getMessage());
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Link expired");
        return problem;
    }

    /**
     * Database unavailable (Tasks 18.1, 18.2): a data-access failure means a
     * dependency is down, not a bug — 503 tells the client to retry later,
     * distinct from both 409 (business conflict) and 500 (unexpected). The
     * cause is logged server-side only; the connection-acquisition timeout
     * is pinned at 5 s in application.yaml so the request cannot hang.
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ProblemDetail handleDataAccessException(
            org.springframework.dao.DataAccessException ex) {
        log.error("data access failure while handling request", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "database temporarily unavailable");
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Service unavailable");
        return problem;
    }

    /**
     * Body over the payload limit (Task 20.1): thrown by the bounded stream
     * for chunked bodies; usually arrives wrapped by the message converter.
     */
    @ExceptionHandler(com.javaded78.urlshorter.config.PayloadTooLargeException.class)
    public ProblemDetail handlePayloadTooLarge(
            com.javaded78.urlshorter.config.PayloadTooLargeException ex) {
        return payloadTooLarge(ex.getMessage());
    }

    /**
     * Unreadable body: 400 for syntactically broken JSON, 413 when the
     * cause chain shows the bounded stream hit the payload limit (chunked
     * oversized bodies, Task 20.1).
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.javaded78.urlshorter.config.PayloadTooLargeException tooLarge) {
                return payloadTooLarge(tooLarge.getMessage());
            }
        }
        log.warn("unreadable request body: {}", ex.toString());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "request body is not readable");
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Invalid request");
        return problem;
    }

    private static ProblemDetail payloadTooLarge(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, detail);
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Payload too large");
        return problem;
    }

    /**
     * Unmapped paths (e.g. retired unversioned API routes) must be a plain
     * 404 — without this, the generic fallback below would turn them into a
     * misleading 500.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ProblemDetail handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "resource not found");
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Not found");
        return problem;
    }

    /**
     * Fallback for everything not covered above — infrastructure failures
     * (PostgreSQL, Redis), unexpected runtime exceptions. The cause is logged
     * server-side only (Task 11.4); the client gets a generic ProblemDetail.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("unexpected error while handling request", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "unexpected error");
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Internal error");
        return problem;
    }

    private static String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
