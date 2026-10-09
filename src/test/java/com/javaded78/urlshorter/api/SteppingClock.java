package com.javaded78.urlshorter.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable {@link Clock} backed by an {@link AtomicReference<Instant>}; time only advances when a
 * test tells it to.
 *
 * <p>This is what lets API integration tests make a link expire <em>deterministically</em> instead
 * of {@code Thread.sleep(1100)} racing the real clock. The reference instant is shared across
 * {@link #withZone(ZoneOffset)} calls, so advancing time in one test is visible to the whole
 * application context — the injected {@code Clock} that drives the read and write services (and the
 * cache TTL policy) all see the same {@code instant()}.
 */
public final class SteppingClock extends Clock {

  private final AtomicReference<Instant> now;
  private final ZoneId zone;

  public SteppingClock(Instant initial) {
    this(initial, ZoneOffset.UTC);
  }

  private SteppingClock(Instant initial, ZoneId zone) {
    this.now = new AtomicReference<>(initial);
    this.zone = zone;
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    // Keep advancing the SAME reference — a new clock must not lose the advanced time.
    return new SteppingClock(now.get(), zone);
  }

  /** Advance time by a positive amount. */
  public void step(Duration amount) {
    now.updateAndGet(current -> current.plus(amount));
  }

  /** Reset to the current real instant — restores isolation between tests. */
  public void reset() {
    now.set(Instant.now());
  }
}
