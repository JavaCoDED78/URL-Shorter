package com.javaded78.urlshorter.config;

import static org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Bounds the raw request body (Task 20.1). Field validation already limits the URL (2048) and alias
 * (64) lengths; without this filter a client could still post an arbitrarily large JSON document.
 *
 * <p>Two paths are covered:
 *
 * <ul>
 *   <li>declared size — {@code Content-Length} over the limit is rejected immediately with a 413
 *       ProblemDetail, the body never read;
 *   <li>undeclared size — chunked bodies are wrapped in a bounded stream that throws {@link
 *       PayloadTooLargeException} once the limit is crossed (mapped to 413 by the global exception
 *       handler via the cause chain).
 * </ul>
 *
 * <p>The limit is configurable: {@code shortener.max-payload-size}, default 16&nbsp;KB — far above
 * the largest legitimate create request (2048-char URL plus JSON overhead).
 */
@Component
public class RequestSizeLimitFilter implements Filter {

  private final long maxPayloadBytes;

  public RequestSizeLimitFilter(ShortenerProperties properties) {
    this.maxPayloadBytes = properties.maxPayloadSize().toBytes();
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest http) || !hasBody(http.getMethod())) {
      chain.doFilter(request, response);
      return;
    }
    long declared = http.getContentLengthLong();
    if (declared > maxPayloadBytes) {
      write413(response);
      return;
    }
    if (declared >= 0) {
      chain.doFilter(request, response);
      return;
    }
    // chunked body: bound the stream instead of trusting the client.
    // Jackson reports stream failures as content errors without the
    // original cause, so detection is flag-based: the bounded stream
    // records the violation and the filter maps it to 413 afterwards.
    BoundedRequestWrapper bounded = new BoundedRequestWrapper(http, maxPayloadBytes);
    try {
      chain.doFilter(bounded, response);
    } catch (Exception ex) {
      if (!causedByPayloadTooLarge(ex)) {
        throw ex;
      }
    }
    if (bounded.violated() && !response.isCommitted()) {
      write413(response);
    }
  }

  private static boolean causedByPayloadTooLarge(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof PayloadTooLargeException) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasBody(String method) {
    return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
  }

  private void write413(ServletResponse response) throws IOException {
    HttpServletResponse http = (HttpServletResponse) response;
    http.setStatus(PAYLOAD_TOO_LARGE.value());
    http.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            PAYLOAD_TOO_LARGE, "request body exceeds the maximum of " + maxPayloadBytes + " bytes");
    problem.setTitle("Payload too large");
    // same dedicated-mapper pattern as RedisLinkCache: this filter runs
    // before MVC, so the web ObjectMapper is not in scope here
    new com.fasterxml.jackson.databind.ObjectMapper().writeValue(http.getOutputStream(), problem);
  }

  private static final class BoundedRequestWrapper extends HttpServletRequestWrapper {

    private final long limit;
    private final java.util.concurrent.atomic.AtomicBoolean violated =
        new java.util.concurrent.atomic.AtomicBoolean();
    private ServletInputStream bounded;

    BoundedRequestWrapper(HttpServletRequest request, long limit) {
      super(request);
      this.limit = limit;
    }

    /** Set once the body crossed the limit — Jackson swallows the marker exception. */
    boolean violated() {
      return violated.get();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (bounded == null) {
        bounded = new BoundedInputStream(getRequest().getInputStream(), limit, violated);
      }
      return bounded;
    }
  }

  /**
   * Pass-through stream that records the limit violation and raises {@link
   * PayloadTooLargeException}; the flag survives even when a JSON parser replaces the exception
   * with a content-level error.
   */
  private static final class BoundedInputStream extends ServletInputStream {

    private final InputStream delegate;
    private final long limit;
    private final java.util.concurrent.atomic.AtomicBoolean violated;
    private long seen;

    BoundedInputStream(
        InputStream delegate, long limit, java.util.concurrent.atomic.AtomicBoolean violated) {
      this.delegate = delegate;
      this.limit = limit;
      this.violated = violated;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value != -1) {
        count(1);
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = delegate.read(buffer, offset, length);
      if (read > 0) {
        count(read);
      }
      return read;
    }

    private void count(int bytes) throws PayloadTooLargeException {
      seen += bytes;
      if (seen > limit) {
        violated.set(true);
        throw new PayloadTooLargeException(
            "request body exceeds the maximum of " + limit + " bytes");
      }
    }

    @Override
    public boolean isFinished() {
      return false;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(jakarta.servlet.ReadListener listener) {
      throw new UnsupportedOperationException("async reads not supported");
    }
  }
}
