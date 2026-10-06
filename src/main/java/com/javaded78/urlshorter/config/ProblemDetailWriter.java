package com.javaded78.urlshorter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.util.Assert;

/**
 * Serializes a {@link ProblemDetail} to an {@link HttpServletResponse} from a single process-wide
 * {@link ObjectMapper}.
 *
 * <p>Centralizes the one place a {@code ProblemDetail} is written out-of-band — before Spring MVC
 * is in scope, e.g. inside the servlet filters that reject a request before a handler runs. A
 * shared mapper guarantees the same serialization (and date/number formatting) as the rest of the
 * application instead of a throwaway instance per rejection, and removes the serializer boilerplate
 * that the filters used to carry.
 *
 * <p>The {@code Content-Type} is {@code application/problem+json} and the status is taken from the
 * {@code ProblemDetail}, so callers only ever describe the error — never the transport.
 */
public final class ProblemDetailWriter {

  private ProblemDetailWriter() {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Writes {@code problem} to the response: sets the HTTP status, the {@code
   * application/problem+json} content type and the serialized body.
   *
   * @param response target response (must not be committed)
   * @param problem detail to serialize
   * @throws IOException if writing the body fails
   * @throws IllegalStateException if the response is already committed
   */
  public static void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
    Objects.requireNonNull(response, "response must not be null");
    Objects.requireNonNull(problem, "problem must not be null");
    Assert.state(
        !response.isCommitted(), "response must not be committed before writing ProblemDetail");
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    MAPPER.writeValue(response.getOutputStream(), problem);
  }
}
