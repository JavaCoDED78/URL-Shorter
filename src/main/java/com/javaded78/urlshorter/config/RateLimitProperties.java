package com.javaded78.urlshorter.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed access to {@code shortener.rate-limiter.*} configuration (Task 20.4): the client-scoped,
 * Redis-backed create-rate limiter.
 *
 * <p>Bound by {@link ConfigurationPropertiesScan}; default values live in {@code application.yaml}
 * so {@code window} and {@code maxRequests} are never {@code null} when the limiter is enabled.
 */
@Validated
@ConfigurationProperties(prefix = "shortener.rate-limiter")
public record RateLimitProperties(
    boolean enabled, @PositiveDuration Duration window, @Positive @Min(1) int maxRequests,
    boolean failOpen, String[] trustedProxies) {}
