/**
 * Domain-level input validators ({@code AliasValidator}, {@code UrlValidator}). These are
 * application-scoped {@code @Component} beans that validate request-side inputs before a link is
 * created; they stay in the domain layer (no infrastructure dependencies) even though they are not
 * repository or service ports.
 */
package com.javaded78.urlshorter.domain.validation;
