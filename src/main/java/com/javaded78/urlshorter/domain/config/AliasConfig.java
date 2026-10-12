package com.javaded78.urlshorter.domain.config;

import java.util.Set;

/**
 * Pure, framework-free alias validation configuration.
 *
 * <p>The {@link com.javaded78.urlshorter.domain.validation.AliasValidator} must not depend on
 * Spring's {@code @ConfigurationProperties} record ({@code
 * com.javaded78.urlshorter.config.ShortenerProperties}); instead the config layer bridges the bound
 * Spring properties into this plain record (see {@code ValidationConfig#aliasConfig}), keeping the
 * domain boundary intact.
 *
 * @param maxLength maximum custom alias length (fits the {@code code VARCHAR(64)} column)
 * @param reserved aliases rejected because they collide with system routes
 */
public record AliasConfig(int maxLength, Set<String> reserved) {}
