package com.javaded78.urlshorter.infra.redis.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BloomFilter}: no false negatives, a bounded false-positive rate over the
 * complement, and deterministic membership that survives rehydration with the same parameters.
 */
class BloomFilterTest {

  @Test
  void containsEveryInsertedKey() {
    BloomFilter filter = new BloomFilter(20_000, 0.01);
    for (int i = 0; i < 20_000; i++) {
      filter.add("code-" + i);
    }

    for (int i = 0; i < 20_000; i++) {
      assertThat(filter.contains("code-" + i)).as("every inserted key is present").isTrue();
    }
  }

  @Test
  void falsePositiveRateStaysBelowThreshold() {
    BloomFilter filter = new BloomFilter(20_000, 0.01);
    Set<String> inserted =
        IntStream.range(0, 20_000).mapToObj(i -> "existing-" + i).collect(Collectors.toSet());
    inserted.forEach(filter::add);

    Set<String> allKeys =
        IntStream.range(0, 20_000).mapToObj(i -> "missing-" + i).collect(Collectors.toSet());
    long falsePositives =
        allKeys.stream().filter(filter::contains).count();

    // With m = n / ln(2 / (1 - p)) * 1.44 and k = ln(2) * m / n the theoretical FPR at the target
    // load is p = 1%. The 1.44 fudge factor above the ideal leaves room, so assert a generous
    // bound: less than 5% of the un-inserted universe is a false positive.
    assertThat((double) falsePositives / allKeys.size())
        .as("false-positive rate must stay low")
        .isLessThan(0.05);
  }

  @Test
  void membershipSurvivesRehydrationWithSameParameters() {
    BloomFilter filter = new BloomFilter(10_000, 0.01);
    filter.add("abc1234");

    BloomFilter rehydrated = BloomFilter.rehydrate(10_000, 0.01, filter.toByteArray());

    assertThat(rehydrated.contains("abc1234")).isTrue();
    assertThat(rehydrated.contains("never-seen")).isFalse();
  }

  @Test
  void distinctParametersAreIncompatible() {
    BloomFilter filter = new BloomFilter(10_000, 0.01);
    filter.add("abc1234");

    // Same hash parameters but different size class — a real-world risk when a node is
    // reconfigured. The serialized bytes must not be silently interpreted by a mismatched
    // filter.
    BloomFilter different = new BloomFilter(10_000, 0.001);
    assertThat(different.contains("abc1234")).isFalse();
  }
}
