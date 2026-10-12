package com.javaded78.urlshorter.infra.redis.flush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.javaded78.urlshorter.domain.counter.ClickCounter;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.domain.service.MetricsRecorder;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;

/**
 * CounterFlushService (Tasks 8.4-8.6): drains all Redis counters atomically, applies one batch
 * UPDATE, and restores drained values to Redis when the database write fails — never losing clicks
 * silently. Infra orchestrator (see {@code infra.redis.flush}).
 */
class CounterFlushServiceTest {

  private final ClickCounter clickCounter = mock(ClickCounter.class);
  private final LinkRepository repository = mock(LinkRepository.class);
  private final MetricsRecorder metrics =
      new MetricsRecorder(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

  private final CounterFlushService flushService =
      new CounterFlushService(clickCounter, repository, metrics);

  @Test
  void flushAppliesBatchUpdateForDrainedCounters() {
    when(clickCounter.drainAll()).thenReturn(Map.of("abc1234", 3L, "other12", 7L));
    when(repository.batchIncrementClicks(any(Map.class))).thenReturn(2);

    int updated = flushService.flush();

    assertThat(updated).isEqualTo(2);
    ArgumentCaptor<Map<String, Long>> captured = ArgumentCaptor.forClass(Map.class);
    verify(repository).batchIncrementClicks(captured.capture());
    assertThat(captured.getValue()).isEqualTo(Map.of("abc1234", 3L, "other12", 7L));
  }

  @Test
  void flushWithNoCountersSkipsDatabase() {
    when(clickCounter.drainAll()).thenReturn(Map.of());

    int updated = flushService.flush();

    assertThat(updated).isZero();
    verify(repository, never()).batchIncrementClicks(any(Map.class));
  }

  @Test
  void dbFailureRestoresDrainedCountersToRedis() {
    when(clickCounter.drainAll()).thenReturn(Map.of("abc1234", 3L));
    when(repository.batchIncrementClicks(any(Map.class)))
        .thenThrow(new DataAccessException("connection lost") {});

    assertThatThrownBy(() -> flushService.flush())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("flush failed");

    // drained value must not be lost: restored for the next flush round
    verify(clickCounter).restore("abc1234", 3L);
  }
}
