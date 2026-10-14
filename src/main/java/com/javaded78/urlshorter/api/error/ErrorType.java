package com.javaded78.urlshorter.api.error;

import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Stable, client-facing categories for every {@link org.springframework.http.ProblemDetail} the API
 * returns.
 *
 * <p>Each constant is a machine-readable, {@link URI#isAbsolute() absolute} URI so clients can
 * branch on the error kind without parsing the human-readable {@code title}. The base URI is {@link
 * #BASE_URI}; appending the {@link #code()} segment yields the {@code type} value sent in the
 * response body. The {@code #code()} is also human-readable (e.g. {@code "validation-error"}),
 * which is handy in logs, traces and error pages.
 *
 * <p>Keep this list in sync with {@code OpenApiContractIT} and {@code ErrorHandlingIT}: every error
 * that reaches the API must appear here so its {@code type}/{@code status}/{@code title} stay
 * stable for clients.
 */
@RequiredArgsConstructor
public enum ErrorType {
  VALIDATION("validation-error", HttpStatus.BAD_REQUEST, "Invalid request"),
  INVALID_REQUEST("invalid-request", HttpStatus.BAD_REQUEST, "Invalid request"),
  CODE_ALREADY_TAKEN("code-already-taken", HttpStatus.CONFLICT, "Code already taken"),
  LINK_NOT_FOUND("link-not-found", HttpStatus.NOT_FOUND, "Link not found"),
  LINK_EXPIRED("link-expired", HttpStatus.GONE, "Link expired"),
  DATABASE_UNAVAILABLE(
      "database-unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
  RATE_LIMITED("rate-limited", HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
  PAYLOAD_TOO_LARGE("payload-too-large", HttpStatus.CONTENT_TOO_LARGE, "Payload too large"),
  RESOURCE_NOT_FOUND("resource-not-found", HttpStatus.NOT_FOUND, "Not found"),
  INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

  private static final URI BASE_URI = URI.create("https://urlshortener.javaded78.dev/errors/");

  private final String code;
  private final HttpStatus status;
  private final String title;

  /** Human-readable, trace-friendly segment, e.g. {@code "validation-error"}. */
  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public String title() {
    return title;
  }

  /** The {@code type} value sent in the response body — an absolute URI. */
  public URI uri() {
    return URI.create(BASE_URI + code);
  }

  /** Case-insensitive lookup by the {@link #code()} segment (used by tests and traces). */
  public static ErrorType byCode(String code) {
    for (ErrorType type : values()) {
      if (type.code.equalsIgnoreCase(code)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unknown error type: " + code);
  }
}
