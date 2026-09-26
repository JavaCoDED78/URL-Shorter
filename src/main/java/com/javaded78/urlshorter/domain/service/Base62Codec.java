package com.javaded78.urlshorter.domain.service;

import org.springframework.stereotype.Component;

/**
 * Stateless Base62 codec: unsigned base-64-style positional notation without
 * the two non-alphanumeric characters.
 *
 * <p>The alphabet is fixed and ordered — {@code 0-9}, {@code A-Z}, {@code a-z}:
 *
 * <pre>
 * index: 0.. 9, 10..35, 36..61
 * char:  0.. 9, A.. Z,  a.. z
 * </pre>
 *
 * <p>A code decodes to a non-negative {@code long}; at most 11 characters are
 * needed ({@code Long.MAX_VALUE} encodes to 11 digits), and decoding guards
 * against the arithmetic overflow that values above it would cause.
 *
 * <p>The component is stateless, so it is trivially thread-safe.
 */
@Component
public class Base62Codec {

    /** Fixed alphabet: digits, then uppercase, then lowercase. */
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final int BASE = ALPHABET.length();

    /**
     * Long.MAX_VALUE encodes to 11 base62 digits, so that is the longest
     * code decode() will accept; anything longer overflows a long.
     */
    private static final int MAX_CODE_LENGTH = 11;

    /**
     * Encodes a non-negative value into its canonical Base62 representation
     * (no leading zeros, {@code "0"} for zero).
     *
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative: " + value);
        }
        StringBuilder sb = new StringBuilder(MAX_CODE_LENGTH);
        long remaining = value;
        do {
            sb.append(ALPHABET.charAt((int) (remaining % BASE)));
            remaining /= BASE;
        } while (remaining > 0);
        return sb.reverse().toString();
    }

    /**
     * Decodes a Base62 code back into the exact value produced by
     * {@link #encode(long)}.
     *
     * @throws IllegalArgumentException if the code is {@code null}, empty,
     *         longer than 10 characters, or contains characters outside the
     *         alphabet
     */
    public long decode(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("code must not be null or empty");
        }
        if (code.length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "code is longer than " + MAX_CODE_LENGTH + " characters: " + code);
        }
        long value = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = digitOf(code.charAt(i), code);
            // Overflow guard: 11 digits can exceed Long.MAX_VALUE, so check
            // before accumulating. The length guard alone is not sufficient.
            if (value > (Long.MAX_VALUE - digit) / BASE) {
                throw new IllegalArgumentException(
                        "code \"" + code + "\" does not fit into a non-negative long");
            }
            value = value * BASE + digit;
        }
        return value;
    }

    private static int digitOf(char c, String code) {
        int digit = ALPHABET.indexOf(c);
        if (digit < 0) {
            throw new IllegalArgumentException(
                    "character '" + c + "' in code \"" + code + "\" is not in the Base62 alphabet");
        }
        return digit;
    }
}
