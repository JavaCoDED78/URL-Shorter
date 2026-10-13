package com.javaded78.urlshorter.infra.redis.flush;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Distributed serialization of the flush across instances (ShedLock). The lock is the only thing
 * standing between two pods draining the same Redis counters at once; {@code @SchedulerLock(name =
 * "clickFlush")} wraps {@code ClickFlushJob#flushClicks()} via {@code PROXY_METHOD} AOP on every
 * invocation, so the lock is acquired whether the call comes from the real {@code @Scheduled}
 * scheduler or a manual test hook.
 *
 * <p>The scheduled flush is disabled for the whole context ({@code shortener.flush.enabled=false})
 * so no {@code @Scheduled} job fires during the test, and the assertions are not racing a real
 * drain. The lock provider runs against real PostgreSQL. Each test starts from a clean {@code
 * shedlock} row. The assertions are on {@link
 * net.javacrumbs.shedlock.core.LockProvider#lock(net.javacrumbs.shedlock.core.LockConfiguration)}
 * directly — that is the exact seam {@code PROXY_METHOD} proxies: non-empty Optional = the guarded
 * flush runs, empty Optional = another instance holds the lock and this instance sheds the cycle.
 */
@SpringBootTest
@TestPropertySource(
    properties = {"shortener.flush.enabled=false", "logging.level.net.javacrumbs.shedlock=DEBUG"})
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class SchedulerLockIT {

  @Autowired CounterFlushService flushService;

  @Autowired LockProvider lockProvider;

  @Autowired JdbcTemplate db;

  @BeforeEach
  void clean() {
    db.update("DELETE FROM shedlock WHERE name = 'clickFlush'");
  }

  @Test
  void lockAcquiredWhenFree() {
    var lock =
        lockProvider.lock(
            new net.javacrumbs.shedlock.core.LockConfiguration(
                java.time.Instant.now(),
                "clickFlush",
                java.time.Duration.ofSeconds(60),
                java.time.Duration.ZERO));

    assertThat(lock).isPresent();
  }

  @Test
  void secondInstanceSkipsWhenLockAlreadyHeld() {
    // another instance holds the lock until the future — the classic second-pod scenario
    db.update(
        "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) "
            + "VALUES ('clickFlush', now() + interval '1 minute', now(), 'another-instance')");

    var lock =
        lockProvider.lock(
            new net.javacrumbs.shedlock.core.LockConfiguration(
                java.time.Instant.now(),
                "clickFlush",
                java.time.Duration.ofSeconds(60),
                java.time.Duration.ZERO));

    assertThat(lock).isEmpty();
  }
}
