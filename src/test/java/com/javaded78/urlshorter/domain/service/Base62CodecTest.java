package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Base62CodecTest {

  private final Base62Codec codec = new Base62Codec();

  @Test
  void encodesKnownValues() {
    assertThat(codec.encode(0)).isEqualTo("0");
    assertThat(codec.encode(9)).isEqualTo("9");
    assertThat(codec.encode(10)).isEqualTo("A");
    assertThat(codec.encode(35)).isEqualTo("Z");
    assertThat(codec.encode(36)).isEqualTo("a");
    assertThat(codec.encode(61)).isEqualTo("z");
    assertThat(codec.encode(62)).isEqualTo("10");
    assertThat(codec.encode(125)).isEqualTo("21");
  }

  @Test
  void decodesKnownCodes() {
    assertThat(codec.decode("0")).isEqualTo(0);
    assertThat(codec.decode("z")).isEqualTo(61);
    assertThat(codec.decode("10")).isEqualTo(62);
  }

  @Test
  void roundTripsAcrossTheRange() {
    long[] values = {
      0, 1, 61, 62, 63, 3843, 3844, 123_456_789L, 839_299_365_868_340_223L, Long.MAX_VALUE
    };
    for (long value : values) {
      assertThat(codec.decode(codec.encode(value))).isEqualTo(value);
    }
  }

  @Test
  void encodesZeroAsSingleDigit() {
    assertThat(codec.encode(0)).isEqualTo("0");
  }

  @Test
  void encodesMaxValueForElevenBase62Digits() {
    // Long.MAX_VALUE is the largest encodable value and takes 11 digits.
    assertThat(codec.encode(Long.MAX_VALUE)).hasSize(11);
    assertThat(codec.decode(codec.encode(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void rejectsNegativeValues() {
    assertThatIllegalArgumentException().isThrownBy(() -> codec.encode(-1));
  }

  @Test
  void rejectsNullCode() {
    assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(null));
  }

  @Test
  void rejectsEmptyCode() {
    assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(""));
  }

  @ParameterizedTest
  @ValueSource(strings = {"!", "abc-123", "ABC 123", "фыв", "0=1"})
  void rejectsForbiddenCharacters(String code) {
    assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(code));
  }

  @Test
  void rejectsCodesLongerThanElevenCharacters() {
    // 12 base62 digits always overflow a long.
    assertThatIllegalArgumentException().isThrownBy(() -> codec.decode("zzzzzzzzzzzz"));
    assertThatIllegalArgumentException().isThrownBy(() -> codec.decode("000000000000"));
  }

  @Test
  void rejectsElevenDigitCodesThatOverflowLong() {
    // 11 digits fit the length limit, but 62^11 - 1 > Long.MAX_VALUE,
    // so the explicit overflow guard must reject them.
    assertThatIllegalArgumentException().isThrownBy(() -> codec.decode("zzzzzzzzzzz"));
    assertThatIllegalArgumentException().isThrownBy(() -> codec.decode("ZZZZZZZZZZZ"));
  }
}
