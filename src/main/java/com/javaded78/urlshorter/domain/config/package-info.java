/**
 * Pure, framework-free domain configuration records.
 *
 * <p>The config layer bridges Spring's {@code @ConfigurationProperties} binding (see {@code
 * com.javaded78.urlshorter.config.ShortenerProperties}) into these plain records, so the
 * application-service layer never depends on a Spring config type and the domain boundary stays
 * intact.
 */
@org.jspecify.annotations.NullMarked
package com.javaded78.urlshorter.domain.config;
