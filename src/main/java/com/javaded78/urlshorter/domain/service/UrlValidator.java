package com.javaded78.urlshorter.domain.service;

import com.javaded78.urlshorter.config.ShortenerProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Validates original URLs (Task 4.2).
 *
 * <p>Uses {@link URI} structural parsing rather than a regex: scheme must be present and in the
 * allowed list (case-insensitive), and a host must exist. Length is bounded by configuration. Regex
 * alone cannot reliably cover scheme/host rules, IDN, ports and userinfo.
 */
@Component
public class UrlValidator {

  private final int maxLength;
  private final Set<String> allowedSchemes;

  public UrlValidator(ShortenerProperties properties) {
    this.maxLength = properties.url().maxLength();
    this.allowedSchemes =
        properties.url().allowedSchemes().stream()
            .map(scheme -> scheme.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
  }

  /**
   * @throws IllegalArgumentException if the URL is null, blank, too long, unparsable, has a
   *     disallowed scheme, or has no host
   */
  public void validate(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("URL must not be null or blank");
    }
    if (url.length() > maxLength) {
      throw new IllegalArgumentException(
          "URL length " + url.length() + " exceeds the maximum of " + maxLength);
    }
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("URL is not syntactically valid: " + ex.getReason());
    }
    String scheme = uri.getScheme();
    if (scheme == null || !allowedSchemes.contains(scheme.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException(
          "URL scheme must be one of " + allowedSchemes + ": " + url);
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalArgumentException("URL must contain a host: " + url);
    }
  }
}
