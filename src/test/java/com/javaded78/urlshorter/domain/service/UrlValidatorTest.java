package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.javaded78.urlshorter.config.ShortenerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private final ShortenerProperties properties = new ShortenerProperties(
            new ShortenerProperties.Url(2048, List.of("http", "https")),
            new ShortenerProperties.Alias(64, java.util.Set.of("api")),
            new ShortenerProperties.Create(5),
            new ShortenerProperties.Cache(java.time.Duration.ofHours(1)),
            org.springframework.util.unit.DataSize.ofKilobytes(16));

    private final UrlValidator validator = new UrlValidator(properties);

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com",
            "https://example.com/path/to/page?query=1&other=2#fragment",
            "https://user:pass@example.com:8080/p",
            "https://[2001:db8::1]:443/ipv6",
    })
    void acceptsValidUrls(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @Test
    void acceptsUrlsUpToTheMaxLength() {
        String urlAtLimit = "https://example.com/" + "a".repeat(2026);

        assertThatCode(() -> validator.validate(urlAtLimit)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
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
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullUrl() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
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
        assertThatCode(() -> validator.validate("HTTPS://example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSchemesOutsideTheAllowedList() {
        assertThatThrownBy(() -> validator.validate("gopher://example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }
}
