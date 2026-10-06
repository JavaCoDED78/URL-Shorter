package com.javaded78.urlshorter.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ShortenerPropertiesValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void validPropertiesHaveNoViolations() {
    ShortenerProperties properties =
        new ShortenerProperties(
            new ShortenerProperties.Url(2048, List.of("https")),
            new ShortenerProperties.Alias(64, Set.of("api")),
            new ShortenerProperties.Create(5),
            new ShortenerProperties.Cache(java.time.Duration.ofMinutes(1)),
            new ShortenerProperties.Code(7),
            DataSize.ofMegabytes(1),
            1024);

    assertThat(validator.validate(properties)).isEmpty();
  }

  @Test
  void invalidCodeLengthIsFlagged() {
    ShortenerProperties properties =
        new ShortenerProperties(
            new ShortenerProperties.Url(2048, List.of("https")),
            new ShortenerProperties.Alias(64, Set.of("api")),
            new ShortenerProperties.Create(5),
            new ShortenerProperties.Cache(java.time.Duration.ofMinutes(1)),
            new ShortenerProperties.Code(999),
            DataSize.ofMegabytes(1),
            1024);

    assertThat(validator.validate(properties)).isNotEmpty();
  }

  @Test
  void invalidUrlLengthIsFlagged() {
    ShortenerProperties properties =
        new ShortenerProperties(
            new ShortenerProperties.Url(-1, List.of("https")),
            new ShortenerProperties.Alias(64, Set.of("api")),
            new ShortenerProperties.Create(5),
            new ShortenerProperties.Cache(java.time.Duration.ofMinutes(1)),
            new ShortenerProperties.Code(7),
            DataSize.ofMegabytes(1),
            1024);

    assertThat(validator.validate(properties)).isNotEmpty();
  }

  @Test
  void negativeCacheTtlIsFlagged() {
    ShortenerProperties properties =
        new ShortenerProperties(
            new ShortenerProperties.Url(2048, List.of("https")),
            new ShortenerProperties.Alias(64, Set.of("api")),
            new ShortenerProperties.Create(5),
            new ShortenerProperties.Cache(java.time.Duration.ofMinutes(-5)),
            new ShortenerProperties.Code(7),
            DataSize.ofMegabytes(1),
            1024);

    assertThat(validator.validate(properties)).isNotEmpty();
  }

  @Test
  void emptyAllowedSchemesIsFlagged() {
    ShortenerProperties properties =
        new ShortenerProperties(
            new ShortenerProperties.Url(2048, List.of()),
            new ShortenerProperties.Alias(64, Set.of("api")),
            new ShortenerProperties.Create(5),
            new ShortenerProperties.Cache(java.time.Duration.ofMinutes(1)),
            new ShortenerProperties.Code(7),
            DataSize.ofMegabytes(1),
            1024);

    assertThat(validator.validate(properties)).isNotEmpty();
  }
}
