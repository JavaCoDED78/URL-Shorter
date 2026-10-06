package com.javaded78.urlshorter.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Bounds the request URI so a crafted path cannot be used to inflate the request-line, the routing
 * stack, or (for the root {@code GET /{code}} endpoint) the code lookup.
 *
 * <p>The URL/alias fields are already field-validated (2048 / 64 characters) and the raw body is
 * bounded by {@link RequestSizeLimitFilter}, but a path segment is parsed before any handler runs and
 * is not subject to those checks. A request whose {@code requestURI.length()} exceeds {@code
 * shortener.max-uri-length} (default 1024 — far above the largest legitimate code of 11 characters)
 * is answered immediately with 413 {@code ProblemDetail} and never reaches the servlet container's
 * routing. The same {@code ProblemDetail} shape is written here as in {@link RequestSizeLimitFilter}
 * (a dedicated, locally-built {@code ObjectMapper}) so the error contract is identical regardless of
 * whether MVC is in scope.
 */
@Component
public class RequestUriLengthFilter implements Filter {

  private final int maxUriLength;

  public RequestUriLengthFilter(ShortenerProperties properties) {
    this.maxUriLength = properties.maxUriLength();
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest http)) {
      chain.doFilter(request, response);
      return;
    }
    int uriLength = http.getRequestURI().length();
    if (uriLength > maxUriLength) {
      write413(response);
      return;
    }
    chain.doFilter(request, response);
  }

  private void write413(ServletResponse response) throws IOException {
    HttpServletResponse http = (HttpServletResponse) response;
    http.setStatus(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE.value());
    http.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
            "request URI exceeds the maximum of " + maxUriLength + " characters");
    problem.setTitle("Request URI too long");
    new com.fasterxml.jackson.databind.ObjectMapper().writeValue(http.getOutputStream(), problem);
  }
}
