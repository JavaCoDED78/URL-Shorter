package com.javaded78.urlshorter.config;

import java.time.Clock;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.auditing.DateTimeProvider;

/**
 * Spring Data JPA auditing (Clock discipline for persistence timestamps).
 *
 * <p>{@code updated_at} on {@code LinkEntity} is stamped by JPA auditing (the {@code @LastModifiedDate}
 * field) — not by a hand-rolled callback — so {@code updated_at} advances on an update path without
 * any {@code Instant.now()} call leaking into the persistence layer.
 *
 * <p>Auditing is driven by a single {@link Clock} bean (declared in
 * {@code UrlShorterApplication}); the {@link DateTimeProvider} delegates to the same clock, so a
 * test that swaps the clock in production also drives the persistence stamp.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
public class JpaAuditingConfig {

  /** Bridges the application {@link Clock} into Spring Data JPA auditing. */
  @Bean
  DateTimeProvider dateTimeProvider(Clock clock) {
    return () -> Optional.of(clock.instant());
  }
}
