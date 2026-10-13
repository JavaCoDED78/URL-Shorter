package com.javaded78.urlshorter.api;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Registers a deterministic {@link SteppingClock} so API integration tests can make a link expire
 * on command instead of {@code Thread.sleep} racing the real clock.
 *
 * <p>Import once per test that needs {@code clock}:
 *
 * <pre>{@code @Import(SteppingClockConfig.class)}</pre>
 */
@TestConfiguration(proxyBeanMethods = false)
public class SteppingClockConfig {

  /** Reference instant all deterministic-expiration assertions run against. */
  public static final Instant REFERENCE = Instant.parse("2025-06-01T00:00:00Z");

  /**
   * Deterministic clock injected over the production {@code Clock} bean declared in {@code
   * UrlShorterApplication} (which would otherwise supply {@code systemUTC()}).
   *
   * <p>The method name is intentionally <em>not</em> {@code clock}: the production bean is already
   * named {@code clock}, and two beans of the same name fail context startup. This bean is the only
   * {@link SteppingClock} in the context, so {@code @Autowired SteppingClock} resolves to it while
   * the production {@code Clock systemUTC()} bean is still present for any {@code @Autowired Clock}
   * (e.g. the {@code CounterFlushService} scheduled flush).
   */
  @Primary
  @Bean
  public SteppingClock steppingClock() {
    return new SteppingClock(REFERENCE);
  }
}
