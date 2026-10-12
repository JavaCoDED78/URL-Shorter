package com.javaded78.urlshorter.domain.config;

/**
 * Pure, framework-free configuration for the write path.
 *
 * <p>The application-service layer must not depend on Spring's {@code @ConfigurationProperties}
 * record ({@code com.javaded78.urlshorter.config.ShortenerProperties}); instead the config layer
 * bridges the bound Spring properties into this plain record (see {@code
 * com.javaded78.urlshorter.config.RateLimitConfig#writeServiceConfig}), keeping the domain boundary
 * intact.
 *
 * @param maxCollisionRetries bounded retries when a generated code collides with an existing row
 */
public record WriteServiceConfig(int maxCollisionRetries) {}
