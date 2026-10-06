package com.javaded78.urlshorter.config;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link java.time.Duration} property that must be strictly positive.
 *
 * <p>{@code jakarta.validation} ships no constraint for {@code java.time.Duration}, so this small
 * custom annotation fills the gap for {@link ShortenerProperties.Cache#ttl}. A {@code null} value
 * is considered valid (use {@link jakarta.validation.constraints.NotNull} when it must be present);
 * the value is bound by Spring Boot from a duration string such as {@code PT5S}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = PositiveDurationValidator.class)
public @interface PositiveDuration {

  String message() default "must be positive";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
