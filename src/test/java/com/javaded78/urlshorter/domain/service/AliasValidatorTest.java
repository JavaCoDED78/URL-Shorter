package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import com.javaded78.urlshorter.config.ShortenerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AliasValidatorTest {

    private final ShortenerProperties properties = new ShortenerProperties(
            new ShortenerProperties.Url(2048, List.of("http", "https")),
            new ShortenerProperties.Alias(64, Set.of("api", "actuator", "swagger")),
            new ShortenerProperties.Create(5),
            new ShortenerProperties.Cache(java.time.Duration.ofHours(1)));

    private final AliasValidator validator = new AliasValidator(properties);

    @ParameterizedTest
    @ValueSource(strings = {
            "my-link",
            "my_link",
            "-leading-dash",
            "trailing-dash-",
            "__double__",
            "MyLink123",
            "a",
            "0",
    })
    void acceptsValidAliases(String alias) {
        assertThat(validator.validateAndNormalize(alias)).isEqualTo(alias.toLowerCase());
    }

    @Test
    void acceptsAliasAtMaxLength() {
        assertThat(validator.validateAndNormalize("a".repeat(64))).isEqualTo("a".repeat(64));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "has space",
            "has/slash",
            "has\\backslash",
            "has.dot",
            "has~tilde",
            "café",
            "emoji🎉",
            "semi;colon",
    })
    void rejectsInvalidAliases(String alias) {
        assertThatThrownBy(() -> validator.validateAndNormalize(alias))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullAlias() {
        assertThatThrownBy(() -> validator.validateAndNormalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlyLongAliases() {
        assertThatThrownBy(() -> validator.validateAndNormalize("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "Api", "actuator", "swagger"})
    void rejectsReservedAliasesRegardlessOfCase(String alias) {
        assertThatThrownBy(() -> validator.validateAndNormalize(alias))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void normalizesToLowercase() {
        assertThat(validator.validateAndNormalize("My-Alias_42")).isEqualTo("my-alias_42");
    }
}
