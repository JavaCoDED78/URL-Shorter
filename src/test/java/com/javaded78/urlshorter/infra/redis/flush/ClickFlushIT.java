package com.javaded78.urlshorter.infra.redis.flush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.domain.service.CounterFlushService;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.domain.counter.ClickCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.TestPropertySource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Atomic Redis → PostgreSQL flush (Tasks 8.3-8.7). Scheduler is disabled:
 * the service is invoked manually for deterministic assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "shortener.flush.enabled=false")
class ClickFlushIT {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ClickCounter clickCounter;

    @Autowired
    CounterFlushService flushService;

    @MockitoSpyBean
    LinkRepository repository;

    @Test
    void tenClicksFlushToDatabase() {
        create("https://example.com/ten", "flush01");
        click("flush01", 10);

        flushService.flush();

        assertThat(detailsClickCount("flush01")).isEqualTo(10);
        assertThat(redis.hasKey("clicks:flush01")).isFalse();
    }

    @Test
    void secondFlushIsNoOp() {
        create("https://example.com/noop", "flush02");
        click("flush02", 5);
        flushService.flush();

        flushService.flush();

        assertThat(detailsClickCount("flush02")).isEqualTo(5);
    }

    @Test
    void multipleLinksFlushTogether() {
        create("https://example.com/a", "flush03");
        create("https://example.com/b", "flush04");
        click("flush03", 4);
        click("flush04", 6);

        flushService.flush();

        assertThat(detailsClickCount("flush03")).isEqualTo(4);
        assertThat(detailsClickCount("flush04")).isEqualTo(6);
    }

    @Test
    void concurrentIncrementsDuringFlushAreNotLost() throws Exception {
        create("https://example.com/race", "flush05");
        int total = 150;

        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            CompletableFuture<?>[] futures = new CompletableFuture<?>[total];
            for (int i = 0; i < total; i++) {
                futures[i] = CompletableFuture.runAsync(() -> redirect("flush05"), pool);
            }
            // flush repeatedly while clicks are landing
            for (int i = 0; i < 5; i++) {
                flushService.flush();
            }
            CompletableFuture.allOf(futures).get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        flushService.flush();

        assertThat(detailsClickCount("flush05")).isEqualTo(total);
    }

    @Test
    void dbFailureRestoresCountersWithoutDataLoss() {
        create("https://example.com/fail", "flush06");
        click("flush06", 7);
        doThrow(new org.springframework.dao.DataAccessException("connection lost") {
        }).when(repository).batchIncrementClickCounts(any(), any());

        assertThatThrownBy(() -> flushService.flush())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flush failed");

        // drained values returned to Redis: nothing silently lost (Task 8.6)
        assertThat(redis.opsForValue().get("clicks:flush06")).isEqualTo("7");
        assertThat(detailsClickCount("flush06")).isEqualTo(0);
    }

    @Test
    void drainIsAtomicAndIdempotentForEmptyCounters() {
        create("https://example.com/drain", "flush07");
        click("flush07", 2);

        Map<String, Long> first = clickCounter.drainAll();
        Map<String, Long> second = clickCounter.drainAll();

        assertThat(first).containsEntry("flush07", 2L);
        assertThat(second).doesNotContainKey("flush07");
        assertThat(redis.hasKey("clicks:flush07")).isFalse();
    }

    private int detailsClickCount(String code) {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/links/" + code, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) response.getBody().get("clickCount")).intValue();
    }

    private void click(String code, int times) {
        for (int i = 0; i < times; i++) {
            redirect(code);
        }
    }

    private void redirect(String code) {
        ResponseEntity<String> response = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW)
                .getForEntity("/" + code, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }

    private void create(String url, String alias) {
        ResponseEntity<Map> created = restTemplate.exchange(
                RequestEntity.post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("originalUrl", url, "alias", alias)),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
