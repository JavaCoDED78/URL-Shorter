package com.javaded78.urlshorter.domain.repository;

import java.time.Clock;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;

/**
 * Test wiring for JPA slices that persist {@code LinkEntity}: the {@code updated_at} stamp relies on
 * Spring Data JPA auditing, which {@code @DataJpaTest} does not enable by default and does not load
 * the application context. This configuration supplies the one piece the slice needs beyond the
 * {@link Clock} it already gets from {@code UrlShorterApplication} and the containers from
 * {@code TestcontainersConfiguration} — the {@link DateTimeProvider} that bridges the clock to
 * auditing — so the slice exercises the exact production auditing path (clock → DateTimeProvider →
 * {@code @LastModifiedDate}). Import alongside {@code TestcontainersConfiguration} and enable
 * auditing on the slice.
 */
@TestConfiguration(proxyBeanMethods = false)
public class JpaAuditingTestConfiguration {

  @Bean
  DateTimeProvider dateTimeProvider(Clock clock) {
    return () -> Optional.of(clock.instant());
  }
}
