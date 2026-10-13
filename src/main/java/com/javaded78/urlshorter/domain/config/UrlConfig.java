package com.javaded78.urlshorter.domain.config;

import java.util.List;

/**
 * Pure, framework-free URL validation configuration.
 *
 * <p>The {@link com.javaded78.urlshorter.domain.validation.UrlValidator} must not depend on
 * Spring's {@code @ConfigurationProperties} record ({@code
 * com.javaded78.urlshorter.config.ShortenerProperties}); instead the config layer bridges the bound
 * Spring properties into this plain record (see {@code ValidationConfig#urlConfig}), keeping the
 * domain boundary intact.
 *
 * @param maxLength maximum accepted original URL length
 * @param allowedSchemes schemes accepted for original URLs
 */
public record UrlConfig(int maxLength, List<String> allowedSchemes) {}
