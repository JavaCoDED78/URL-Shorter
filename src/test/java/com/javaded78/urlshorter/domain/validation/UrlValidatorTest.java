package com.javaded78.urlshorter.domain.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.config.UrlConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

  private final ShortenerProperties properties =
      new ShortenerProperties(
          new ShortenerProperties.Url(2048, List.of("http", "https")),
          new ShortenerProperties.Alias(64, java.util.Set.of("api")),
          new ShortenerProperties.Create(5),
          new ShortenerProperties.Cache(
              java.time.Duration.ofHours(1),
              java.time.Duration.ofSeconds(45),
              1_000_000L,
              0.0001,
              0.15),
          new ShortenerProperties.Code(7),
          org.springframework.util.unit.DataSize.ofKilobytes(16),
          1024);

  private final UrlValidator validator =
      new UrlValidator(
          new UrlConfig(properties.url().maxLength(), properties.url().allowedSchemes()));

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://example.com",
        "http://example.com",
        "https://example.com/path/to/page?query=1&other=2#fragment",
        "https://user:pass@example.com:8080/p",
        "https://[2001:db8::1]:443/ipv6",
        // Internal-address rejection is a no-op when DNS cannot resolve (UnknownHostException
        // fails open), so the unit tests cover the address classification with IP literals.
        "https://1.2.3.4/path",
        "http://[2001:db8::1]",
      })
  void acceptsValidUrls(String url) {
    assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.0.0.1/",
        "http://127.0.0.1:8080/actuator",
        "http://[::1]/",
        "http://[::1]:8080/actuator",
        "https://user:pass@[::1]:443/",
        "http://169.254.169.254/latest/meta-data/",
        "http://10.0.0.5/",
        "http://192.168.1.1/",
        "http://172.16.0.1/",
        "http://172.31.255.255/",
        "http://0.0.0.0/",
        "http://0.0.0.0:8080/",
        "http://[::]/",
        "http://2130706433/",
        "http://255.255.255.255/",
      })
  void rejectsUrlsPointingAtInternalAddresses(String url) {
    assertThatThrownBy(() -> validator.validate(url))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("internal");
  }

  @Test
  void acceptsUrlsUpToTheMaxLength() {
    String urlAtLimit = "https://example.com/" + "a".repeat(2026);

    assertThatCode(() -> validator.validate(urlAtLimit)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "example.com",
        "www.example.com/path",
        "htp://example.com",
        "ftp://example.com/file",
        "javascript:alert(1)",
        "data:text/html,<h1>x</h1>",
        "https://",
        "https:///path-only",
        "https://?query-only",
        "https://#fragment-only",
        "https://exa mple.com",
        "https://example.com/ space",
      })
  void rejectsInvalidUrls(String url) {
    assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullUrl() {
    assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOverlyLongUrls() {
    String tooLong = "https://example.com/" + "a".repeat(2048);

    assertThatThrownBy(() -> validator.validate(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("length");
  }

  @Test
  void schemeCheckIsCaseInsensitive() {
    assertThatCode(() -> validator.validate("HTTPS://example.com")).doesNotThrowAnyException();
  }

  @Test
  void rejectsSchemesOutsideTheAllowedList() {
    assertThatThrownBy(() -> validator.validate("gopher://example.com"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scheme");
  }
}
