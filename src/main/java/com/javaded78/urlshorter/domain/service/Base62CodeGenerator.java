package com.javaded78.urlshorter.domain.service;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Base62 code generator (Task 3.4 implementation).
 *
 * <p>Draws a uniform value from the whole {@code [62^(length-1), 62^length)}
 * range and encodes it, so every code has exactly {@code length} characters
 * and the full keyspace is used — no leading-zero bias, no short codes.
 *
 * <p>Thread-safety: {@link SecureRandom} is thread-safe, the codec is
 * stateless, and the configuration is immutable after construction.
 */
@Component
public class Base62CodeGenerator implements CodeGenerator {

    private final Base62Codec codec;
    private final RandomGenerator random;
    private final int codeLength;
    private final long lowerBound;

    @Autowired
    public Base62CodeGenerator(Base62Codec codec,
            @Value("${shortener.code.length:7}") int codeLength) {
        this(codec, new SecureRandom(), codeLength);
    }

    /** Package-visible for deterministic tests with a seeded generator. */
    Base62CodeGenerator(Base62Codec codec, RandomGenerator random, int codeLength) {
        if (codeLength < 1 || codeLength > 11) {
            throw new IllegalArgumentException(
                    "code length must be between 1 and 11 (a long holds at most 11 base62 digits): " + codeLength);
        }
        this.codec = codec;
        this.random = random;
        this.codeLength = codeLength;
        this.lowerBound = pow62(codeLength - 1);
    }

    @Override
    public String generate() {
        // lowerBound guarantees exactly codeLength characters after encoding.
        long bound = lowerBound * 62;
        return codec.encode(random.nextLong(lowerBound, bound));
    }

    int codeLength() {
        return this.codeLength;
    }

    private static long pow62(int exponent) {
        long result = 1;
        for (int i = 0; i < exponent; i++) {
            result *= 62;
        }
        return result;
    }
}
