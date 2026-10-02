package com.javaded78.urlshorter.domain.service;

import com.javaded78.urlshorter.config.ShortenerProperties;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates and normalizes custom aliases (Task 4.3).
 *
 * <p>Alias format: {@code [a-zA-Z0-9_-]+} (the character class is checked with a regex — it is a
 * simple character set, not structural validation), length bounded by configuration.
 *
 * <p>Case policy: aliases are case-insensitive and normalized to lowercase. Mixed-case forms
 * ({@code ABC} vs {@code abc}) therefore address the same link; migration V2 enforces
 * case-insensitive uniqueness in PostgreSQL.
 *
 * <p>Aliases equal to a system route ({@code api}, {@code swagger}, ...) are rejected so user codes
 * can never shadow application endpoints.
 */
@Component
public class AliasValidator {

  private static final Pattern ALLOWED = Pattern.compile("^[a-zA-Z0-9_-]+$");

  private final int maxLength;
  private final Set<String> reserved;

  public AliasValidator(ShortenerProperties properties) {
    this.maxLength = properties.alias().maxLength();
    this.reserved =
        properties.alias().reserved().stream()
            .map(word -> word.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
  }

  /**
   * Validates the alias and returns its normalized (lowercase) form.
   *
   * @throws IllegalArgumentException if the alias is null, blank, contains characters outside
   *     {@code [a-zA-Z0-9_-]}, exceeds the maximum length, or is reserved
   */
  public String validateAndNormalize(String alias) {
    if (alias == null || alias.isBlank()) {
      throw new IllegalArgumentException("alias must not be null or blank");
    }
    if (alias.length() > maxLength) {
      throw new IllegalArgumentException(
          "alias length " + alias.length() + " exceeds the maximum of " + maxLength);
    }
    if (!ALLOWED.matcher(alias).matches()) {
      throw new IllegalArgumentException(
          "alias may only contain letters, digits, '-' and '_': " + alias);
    }
    String normalized = alias.toLowerCase(Locale.ROOT);
    if (reserved.contains(normalized)) {
      throw new IllegalArgumentException("alias is reserved: " + normalized);
    }
    return normalized;
  }
}
