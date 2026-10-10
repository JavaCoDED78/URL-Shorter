package com.javaded78.urlshorter.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Distributed serialization of {@code @Scheduled} tasks across multiple instances.
 *
 * <p>The click flush (and any future periodic job) is guarded by a lock written to the existing
 * PostgreSQL {@code shedlock} table (migration V3), so a multi-pod deployment drains Redis exactly
 * once per cycle instead of once per replica. {@code @EnableSchedulerLock} (default {@code
 * PROXY_METHOD}) is on the entry point so the lock is acquired via AOP on every call to {@code
 * ClickFlushJob#flushClicks()}, whether that call comes from the real {@code @Scheduled} scheduler or
 * a test hook; {@code @SchedulerLock(name = "clickFlush")} wraps that method. A {@code
 * lockAtMostFor} is set on {@code @EnableSchedulerLock} so a crash that holds the lock cannot pin it
 * forever.
 */
@Configuration
public class ShedLockConfig {

  @Value("${shortener.shedlock.locked-by:instance}")
  private String lockedBy;

  @Bean
  LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(jdbcTemplate)
            .withTableName("shedlock")
            .withLockedByValue(lockedBy)
            .usingDbTime()
            .build());
  }
}
