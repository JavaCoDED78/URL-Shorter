package com.javaded78.urlshorter.config;

import java.io.IOException;

/**
 * Thrown by {@link RequestSizeLimitFilter}'s bounded input stream when a body without a declared
 * {@code Content-Length} (chunked transfer) exceeds the configured payload limit. An {@link
 * IOException} so it surfaces through message converters as the root cause of an {@code
 * HttpMessageNotReadableException}; {@link
 * com.javaded78.urlshorter.api.error.GlobalExceptionHandler} walks the cause chain and maps it to
 * 413.
 */
public class PayloadTooLargeException extends IOException {

  public PayloadTooLargeException(String message) {
    super(message);
  }
}
