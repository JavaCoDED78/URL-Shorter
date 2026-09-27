package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.stream.IntStream;

import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.infra.redis.counter.ClickCounter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;

/**
 * CounterFlushService (Tasks 8.4-8.6): drains all Redis counters atomically,
 * applies one batch UPDATE, and restores drained values to Redis when the
 * database write fails — never losing clicks silently.
 */
class CounterFlushServiceTest {

    private final ClickCounter clickCounter = mock(ClickCounter.class);
    private final LinkRepository repository = mock(LinkRepository.class);
    private final MetricsRecorder metrics =
            new MetricsRecorder(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    private final CounterFlushService flushService = new CounterFlushService(clickCounter, repository, metrics);

    @Test
    void flushAppliesBatchUpdateForDrainedCounters() {
        when(clickCounter.drainAll()).thenReturn(Map.of("abc1234", 3L, "other12", 7L));
        when(repository.batchIncrementClickCounts(any(), any())).thenReturn(2);

        int updated = flushService.flush();

        assertThat(updated).isEqualTo(2);
        // reassemble code -> increment; map iteration order is not guaranteed
        ArgumentCaptor<String[]> codes = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<Long[]> increments = ArgumentCaptor.forClass(Long[].class);
        verify(repository).batchIncrementClickCounts(codes.capture(), increments.capture());
        Map<String, Long> applied = IntStream.range(0, codes.getValue().length)
                .boxed()
                .collect(java.util.stream.Collectors.toMap(
                        i -> codes.getValue()[i], i -> increments.getValue()[i]));
        assertThat(applied).isEqualTo(Map.of("abc1234", 3L, "other12", 7L));
    }

    @Test
    void flushWithNoCountersSkipsDatabase() {
        when(clickCounter.drainAll()).thenReturn(Map.of());

        int updated = flushService.flush();

        assertThat(updated).isZero();
        verify(repository, never()).batchIncrementClickCounts(any(), any());
    }

    @Test
    void dbFailureRestoresDrainedCountersToRedis() {
        when(clickCounter.drainAll()).thenReturn(Map.of("abc1234", 3L));
        when(repository.batchIncrementClickCounts(any(), any()))
                .thenThrow(new DataAccessException("connection lost") {
                });

        assertThatThrownBy(() -> flushService.flush())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flush failed");

        // drained value must not be lost: restored for the next flush round
        verify(clickCounter).restore("abc1234", 3L);
    }
}
