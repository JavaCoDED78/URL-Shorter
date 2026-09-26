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
 * <p>Only stable, client-facing categories are exposed (400 invalid request,
 * 404 not found, 409 conflict); internals stay out of the response. Sprint 11
 * will formalize the full exception hierarchy and enrich these mappings.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    private static String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
