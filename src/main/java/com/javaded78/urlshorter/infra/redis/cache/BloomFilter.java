package com.javaded78.urlshorter.infra.redis.cache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Probabilistic membership set over short-link codes (Task 21.2).
 *
 * <p>Uses the standard bit-array Bloom filter with {@code k} hash probes per key, computed by the
 * Kirsch–Mitzenmacher double-hashing trick from a single {@link MurmurHash3 x64_128} pass: two
 * 64-bit hashes {@code h1},{@code h2} yield the probe indices {@code (h1 + i*h2) mod m}. No false
 * negatives are possible (a key that was inserted always reports {@code contains == true}); the
 * false-positive rate is bounded by the configured {@code falsePositiveRate} at the configured
 * expected number of inserts.
 *
 * <p>Backed by a Redis bitmap so it survives restarts and is shared across replicas — a fresh
 * process recomputes nothing, it reads the bitmap back from {@code bloom:codes}. The serialized
 * form carries its own parameters ({@code expectedInserts}, {@code falsePositiveRate}, {@code k},
 * {@code m}); rehydration with mismatched parameters is rejected rather than silently misreading
 * the bit array.
 *
 * <p>Not thread-safe on its own for cross-process updates — the authoritative set lives in Redis;
 * the in-memory instance is the fast short-circuit that every replica maintains in sync via the
 * bitmap.
 */
public final class BloomFilter {

  private static final long MAGIC = 0x424C4F4D5F46494DL; // "BLOM-FIM"
  private static final int VERSION = 1;

  private long m; // number of bits
  private int k; // number of hash probes
  private long[] longs; // backing storage, ceil(m/64) longs
  private long expectedInserts;
  private double falsePositiveRate;

  /**
   * Creates a filter sized for {@code expectedInserts} keys at the given false-positive rate. The
   * bit-array size {@code m} and probe count {@code k} follow the optimal formulas
   * {@code m = ceil(-n * ln(p) / ln(2)^2)} and {@code k = round(m/n * ln(2))}, with a 1.44x
   * padding factor so the rate stays low even as the filter fills.
   */
  public BloomFilter(long expectedInserts, double falsePositiveRate) {
    if (expectedInserts <= 0) {
      throw new IllegalArgumentException("expectedInserts must be positive");
    }
    if (falsePositiveRate <= 0.0 || falsePositiveRate >= 1.0) {
      throw new IllegalArgumentException("falsePositiveRate must be in (0, 1)");
    }
    this.expectedInserts = expectedInserts;
    this.falsePositiveRate = falsePositiveRate;
    double mReal =
        Math.ceil(-expectedInserts * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2)));
    // 1.44x padding factor so the observed FPR stays low even as the filter fills toward capacity.
    final double padding = 1.44;
    m = Math.max(1, (long) (mReal * padding));
    k = (int) Math.max(1.0, Math.round(m / expectedInserts * Math.log(2)));
    longs = new long[(int) Math.ceil(m / 64.0)];
  }

  /**
   * Adds a key. Idempotent per key.
   *
   * @param code the code to record
   */
  public void add(String code) {
    long[] idxs = hashes(code);
    for (long idx : idxs) {
      longs[(int) (idx / 64)] |= 1L << (idx % 64);
    }
  }

  /**
   * Reports membership. A {@code true} answer may be a false positive; a {@code false} answer is
   * always correct.
   *
   * @param code the code to test
   * @return {@code true} if the key was inserted (or a false positive), {@code false} if definitely
   *     absent
   */
  public boolean contains(String code) {
    for (long idx : indices(code)) {
      if ((longs[(int) (idx / 64)] & (1L << (idx % 64))) == 0L) {
        return false;
      }
    }
    return true;
  }

  /** Serializes the current bit array and parameters for Redis storage. */
  public byte[] toByteArray() {
    try (ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo)) {
      out.writeLong(MAGIC);
      out.writeInt(VERSION);
      out.writeLong(expectedInserts);
      out.writeLong(Double.doubleToRawLongBits(falsePositiveRate));
      out.writeInt(k);
      out.writeLong(m);
      out.writeInt(longs.length);
      for (long value : longs) {
        out.writeLong(value);
      }
      return bo.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("failed to serialize bloom filter", ex);
    }
  }

  /**
   * Restores a filter from {@link #toByteArray()}. Validates the header and that the caller-supplied
   * {@code expectedInserts}/{@code falsePositiveRate} match the ones stored at write time — a mismatch
   * indicates a node that was reconfigured mid-flight and would silently misread the bit array.
   *
   * @param expectedInserts expected number of inserts (must match the serialized value)
   * @param falsePositiveRate expected false-positive rate (must match the serialized value)
   * @param data serialized bytes from {@link #toByteArray()}
   * @return the restored filter
   * @throws IllegalArgumentException if the stream is malformed or the parameters disagree
   */
  public static BloomFilter rehydrate(
      long expectedInserts, double falsePositiveRate, byte[] data) {
    try (DataInputStream in =
        new DataInputStream(new ByteArrayInputStream(data))) {
      long magic = in.readLong();
      int version = in.readInt();
      long storedInserts = in.readLong();
      double storedFpr = Double.longBitsToDouble(in.readLong());
      int storedK = in.readInt();
      long storedM = in.readLong();
      int numLongs = in.readInt();

      if (magic != MAGIC || version != VERSION) {
        throw new IllegalArgumentException("not a bloom filter stream");
      }
      if (storedInserts != expectedInserts || storedFpr != falsePositiveRate) {
        throw new IllegalArgumentException(
            "bloom filter parameters changed between write and read"
                + " (inserts "
                + storedInserts
                + "->"
                + expectedInserts
                + ", fpr "
                + storedFpr
                + "->"
                + falsePositiveRate
                + ")");
      }
      BloomFilter filter = new BloomFilter(expectedInserts, falsePositiveRate);
      // Overwrite the freshly-computed parameters with the serialized ones so the bitmap shape
      // matches exactly what was written.
      filter.expectedInserts = storedInserts;
      filter.falsePositiveRate = storedFpr;
      filter.k = storedK;
      filter.m = storedM;
      long[] storage = new long[numLongs];
      for (int i = 0; i < numLongs; i++) {
        storage[i] = in.readLong();
      }
      filter.longs = storage;
      return filter;
    } catch (IOException ex) {
      throw new IllegalArgumentException("corrupt bloom filter stream", ex);
    }
  }

  long[] indices(String code) {
    long[] h = murmur3_x64_128(code, 0x9E3779B97F4A7C15L);
    long h1 = h[0];
    long h2 = h[1];
    long[] idxs = new long[k];
    for (int i = 0; i < k; i++) {
      idxs[i] = ((h1 + (long) i * h2) & Long.MAX_VALUE) % m;
    }
    return idxs;
  }

  /** Returns the number of bits in the array (for diagnostics). */
  public long size() {
    return m;
  }

  /** Returns the probe count (for diagnostics). */
  public int probes() {
    return k;
  }

  private long[] hashes(String code) {
    long[] h = murmur3_x64_128(code, 0x9E3779B97F4A7C15L);
    long h1 = h[0];
    long h2 = h[1];
    long[] idxs = new long[k];
    for (int i = 0; i < k; i++) {
      // Non-negative index via the sign bit, then modulo the bit width.
      long combined = h1 + (long) i * h2;
      idxs[i] = (combined & Long.MAX_VALUE) % m;
    }
    return idxs;
  }

  /**
   * MurmurHash3 x64_128: a single pass over the UTF-8 bytes returns two independent 64-bit hashes.
   * The Kirsch–Mitzenmacher trick derives {@code k} indices from this pair.
   */
  private static long[] murmur3_x64_128(String s, long seed) {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    int n = bytes.length;
    long h1 = seed;
    long h2 = seed;

    long c1 = 0x87c37b91114253d5L;
    long c2 = 0x4cf5ad432745937fL;

    int read = 0;
    while (n >= 16) {
      long m0 = loadLongLittleEndian(bytes, read);
      long m1 = loadLongLittleEndian(bytes, read + 8);
      h1 ^= m0;
      h1 = rotateLeft(h1, 31);
      h1 *= c1;

      h2 ^= m1;
      h2 = rotateLeft(h2, 32);
      h2 *= c2;

      h1 ^= h2 >>> 32;
      h2 ^= h1 >>> 31;

      h1 ^= m0 >>> 32;
      h1 ^= m1;
      h2 ^= m1 >>> 32;
      h2 ^= m0;

      read += 16;
      n -= 16;
    }

    long v0 = 0;
    long v1 = 0;
    for (int i = 0; i < n; i++) {
      int shift = i * 8;
      v0 ^= (bytes[read + i] & 0xFFL) << shift;
      v1 ^= (bytes[read + i] & 0xFFL) << (56 - shift);
    }

    h1 ^= v0;
    h1 ^= (long) n;
    h2 ^= v1;
    h2 ^= (long) n;

    h1 += h2;
    h2 += h1;
    h1 = fmix64(h1);
    h2 = fmix64(h2);
    h1 += h2;
    h2 += h1;

    return new long[] {h1, h2};
  }

  private static long loadLongLittleEndian(byte[] bytes, int offset) {
    ByteBuffer buf = ByteBuffer.wrap(bytes, offset, 8);
    return buf.order(ByteOrder.LITTLE_ENDIAN).getLong();
  }

  private static long rotateLeft(long value, int shift) {
    return (value << shift) | (value >>> (64 - shift));
  }

  private static long fmix64(long k) {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccdL;
    k ^= k >>> 33;
    k *= 0xc4ceb9fe1a85ec53L;
    k ^= k >>> 33;
    return k;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BloomFilter other)) {
      return false;
    }
    return m == other.m
        && k == other.k
        && expectedInserts == other.expectedInserts
        && Double.compare(falsePositiveRate, other.falsePositiveRate) == 0
        && Arrays.equals(longs, other.longs);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(longs);
  }
}
