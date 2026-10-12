package com.javaded78.urlshorter.infra.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import com.javaded78.urlshorter.infra.redis.flush.CounterFlushService;
import com.javaded78.urlshorter.domain.service.ReadLinkService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Virtual threads contract (Tasks 14.1–14.2): HTTP request handling must execute on virtual
 * threads, and a concurrent burst must not inflate the server platform thread count. Virtual
 * threads make blocking cheap but do not widen the PostgreSQL pool — see ConnectionPoolIT (14.3).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "shortener.flush.interval=PT1S")
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class VirtualThreadsIT {

  private static final int BURST_SIZE = 60;
  private static final long HANDLER_DELAY_MS = 300;

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @MockitoSpyBean private ReadLinkService linkService;

  @MockitoSpyBean private CounterFlushService flushService;

  private TestRestTemplate rest;

  @BeforeEach
  void setUp() {
    // Client-side threads are excluded from platform-thread accounting:
    // the JDK HttpClient used by TestRestTemplate has its own cached
    // executor that would otherwise mask the server-side signal.
    rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
  }

  @Test
  void requestHandlingRunsOnVirtualThreads() throws Exception {
    create("https://example.com", "vt-direct");
    AtomicBoolean virtualThreadSeen = new AtomicBoolean(false);
    doAnswer(
            invocation -> {
              virtualThreadSeen.set(Thread.currentThread().isVirtual());
              Thread.sleep(HANDLER_DELAY_MS);
              return invocation.callRealMethod();
            })
        .when(linkService)
        .resolve(org.mockito.ArgumentMatchers.anyString());

    runConcurrentBurst("/vt-direct");

    assertThat(virtualThreadSeen).as("redirect handler must run on a virtual thread").isTrue();
  }

  @Test
  void concurrentBurstDoesNotInflatePlatformThreads() throws Exception {
    create("https://example.com", "vt-burst");
    doAnswer(
            invocation -> {
              Thread.sleep(HANDLER_DELAY_MS);
              return invocation.callRealMethod();
            })
        .when(linkService)
        .resolve(org.mockito.ArgumentMatchers.anyString());

    Supplier<Integer> serverThreads = VirtualThreadsIT::serverPlatformThreadCount;
    int baseline = serverThreads.get();
    AtomicInteger maxThreads = new AtomicInteger(baseline);
    AtomicBoolean sampling = new AtomicBoolean(true);
    Thread sampler =
        Thread.ofPlatform()
            .start(
                () -> {
                  while (sampling.get()) {
                    maxThreads.accumulateAndGet(serverThreads.get(), Math::max);
                    try {
                      Thread.sleep(25);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                });

    runConcurrentBurst("/vt-burst");
    sampling.set(false);
    sampler.join(TimeUnit.SECONDS.toMillis(5));

    // Platform threads would grow by ~BURST_SIZE without virtual threads;
    // with them the server carrier pool stays flat (small margin for
    // JIT/GC/Testcontainers background activity).
    assertThat(maxThreads.get())
        .as("server platform threads during %d-way burst (baseline %d)", BURST_SIZE, baseline)
        .isLessThan(baseline + 50);
  }

  private static int serverPlatformThreadCount() {
    return (int)
        Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> !thread.isVirtual())
            .filter(thread -> !thread.getName().startsWith("HttpClient-"))
            .count();
  }

  @Test
  void scheduledFlushRunsOnVirtualThread() throws Exception {
    create("https://example.com", "vt-sched");
    AtomicBoolean virtualThreadSeen = new AtomicBoolean(false);
    doAnswer(
            invocation -> {
              virtualThreadSeen.set(Thread.currentThread().isVirtual());
              return invocation.callRealMethod();
            })
        .when(flushService)
        .flush();

    // flush interval is PT1S in this test class — a few scheduled cycles
    // are enough to observe the scheduler thread.
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
    while (!virtualThreadSeen.get() && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }

    assertThat(virtualThreadSeen).as("scheduled flush must run on a virtual thread").isTrue();
  }

  private void runConcurrentBurst(String path) throws Exception {
    CountDownLatch ready = new CountDownLatch(BURST_SIZE);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(BURST_SIZE);
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < BURST_SIZE; i++) {
      // Workers are virtual too — the metric under test is the SERVER
      // platform thread count, not the client's.
      Thread worker =
          Thread.ofVirtual()
              .start(
                  () -> {
                    ready.countDown();
                    try {
                      start.await();
                      rest.getForEntity(url(path), String.class);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    } finally {
                      done.countDown();
                    }
                  });
      workers.add(worker);
    }
    assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
  }

  private void create(String originalUrl, String alias) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> response =
        restTemplate.exchange(
            url("/api/v1/links"),
            HttpMethod.POST,
            new HttpEntity<>(Map.of("originalUrl", originalUrl, "alias", alias), headers),
            Map.class);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
