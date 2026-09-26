package com.javaded78.urlshorter.domain.service;

/**
 * Generates short link codes (Task 3.4 abstraction).
 *
 * <p>Kept as an interface so the random source and collision handling can be
 * tested separately from the encoding: production uses {@link Base62CodeGenerator}
 * backed by {@link java.security.SecureRandom}, tests can substitute a
 * deterministic generator.
 */
public interface CodeGenerator {

    /**
     * Generates a new code of the configured length, encoded in Base62.
     */
    String generate();
}
