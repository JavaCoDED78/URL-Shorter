package com.javaded78.urlshorter.config;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Duration;

/**
 * {@link PositiveDuration} implementation for {@link java.time.Duration}: passes only for strictly
 * positive values. {@code null} is treated as valid so this annotation can be combined with {@link
 * jakarta.validation.constraints.NotNull} when presence is required.
 */
public class PositiveDurationValidator implements ConstraintValidator<PositiveDuration, Duration> {

  @Override
  public boolean isValid(Duration value, ConstraintValidatorContext context) {
    return value == null || value.isPositive();
  }
}
