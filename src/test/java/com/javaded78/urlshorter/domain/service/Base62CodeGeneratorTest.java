package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Base62CodeGeneratorTest {

  private final Base62Codec codec = new Base62Codec();

  @Test
  void generatesCodesOfConfiguredLength() {
    CodeGenerator generator = new Base62CodeGenerator(codec, new SecureRandom(), 7);

    for (int i = 0; i < 1_000; i++) {
      assertThat(generator.generate()).hasSize(7);
    }
  }

  @Test
  void generatesAlphabetOnlyCodes() {
    Base62CodeGenerator generator = new Base62CodeGenerator(codec, new SecureRandom(), 7);

    for (int i = 0; i < 1_000; i++) {
      // round-trips through the codec without an exception
      assertThat(codec.decode(generator.generate())).isNotNegative();
    }
  }

  @Test
  void generatesDistinctCodesInPractice() {
    Base62CodeGenerator generator = new Base62CodeGenerator(codec, new SecureRandom(), 7);
    Set<String> codes = new HashSet<>();

    for (int i = 0; i < 10_000; i++) {
      codes.add(generator.generate());
    }

    assertThat(codes).hasSize(10_000);
  }

  @Test
  void generatesCodesAcrossTheWholeConfiguredRange() {
    // Deterministic check: canonical base62 has no leading zeros, so a
    // 7-digit code can start with any of the 61 non-'0' digits. Over a
    // large sample all of them must appear, proving the generator uses
    // the full [62^6, 62^7) keyspace rather than a biased subrange.
    Base62CodeGenerator generator = new Base62CodeGenerator(codec, new SecureRandom(), 7);
    Set<Character> firstDigits = new HashSet<>();
    for (int i = 0; i < 50_000; i++) {
      firstDigits.add(generator.generate().charAt(0));
    }
    assertThat(firstDigits).hasSize(61);
    assertThat(firstDigits).doesNotContain('0');
  }

  @Test
  void rejectsLengthBelowOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Base62CodeGenerator(codec, new SecureRandom(), 0));
  }

  @Test
  void rejectsLengthAboveTen() {
    // 11 base62 digits cannot round-trip through a single non-negative long
    // (62^11 overflows), so the generator caps at length 10.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Base62CodeGenerator(codec, new SecureRandom(), 11));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Base62CodeGenerator(codec, new SecureRandom(), 12));
  }

  @Test
  void lengthTenRoundTrips() {
    // 10 is the largest length that both encodes into a long and decodes back exactly.
    Base62CodeGenerator generator = new Base62CodeGenerator(codec, new SecureRandom(), 10);
    for (int i = 0; i < 1_000; i++) {
      String code = generator.generate();
      assertThat(code).hasSize(10);
      assertThat(codec.decode(code)).isNotNegative();
    }
  }
}
