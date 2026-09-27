package com.javaded78.urlshorter.infra.redis.counter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.javaded78.urlshorter.TestcontainersConfiguration;
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

/**
 * Click counting semantics (Tasks 7.2-7.4): every successful redirect
 * increments {@code clicks:{code}}; 404/410 and malformed requests do not;
 * concurrent increments must not lose clicks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class ClickCounterIT {

    private static final int PARALLEL_CLICKS = 200;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    StringRedisTemplate redis;

    /** Redirect assertions need the raw 302; the default client follows. */
    private TestRestTemplate rest;

    @org.junit.jupiter.api.BeforeEach
    void doNotFollowRedirects() {
        rest = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW);
    }

    @Test
    void successfulRedirectsIncrementCounter() {
        create("https://example.com/count", "click01");
        redirect("click01");
        redirect("click01");
        redirect("click01");

        assertThat(redis.opsForValue().get("clicks:click01")).isEqualTo("3");
    }

    @Test
    void unknownCodeDoesNotIncrement() {
        ResponseEntity<String> response = redirect("no-such-code");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(redis.hasKey("clicks:no-such-code")).isFalse();
    }

    @Test
    void expiredLinkDoesNotIncrement() throws InterruptedException {
        // Links cannot be created already expired (Task 11.3): create with a
        // near-future expiration and let it lapse.
        create("https://example.com/old", "click02", Instant.now().plusSeconds(1).toString());
        Thread.sleep(1100);

        ResponseEntity<String> response = redirect("click02");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(redis.hasKey("clicks:click02")).isFalse();
    }

    @Test
    void concurrentIncrementsLoseNoClicks() throws Exception {
        create("https://example.com/race", "click03");

        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            CompletableFuture<?>[] futures = new CompletableFuture<?>[PARALLEL_CLICKS];
            for (int i = 0; i < PARALLEL_CLICKS; i++) {
                futures[i] = CompletableFuture.runAsync(() -> redirect("click03"), pool);
            }
            CompletableFuture.allOf(futures).get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(redis.opsForValue().get("clicks:click03"))
                .isEqualTo(String.valueOf(PARALLEL_CLICKS));
    }

    @Test
    void counterKeyIsCaseInsensitiveWithRedirect() {
        create("https://example.com/case", "Click04");
        redirect("CLICK04");

        assertThat(redis.opsForValue().get("clicks:click04")).isEqualTo("1");
    }

    private ResponseEntity<String> redirect(String code) {
        return rest.getForEntity("/" + code, String.class);
    }

    private void create(String url, String alias) {
        create(url, alias, null);
    }

    private void create(String url, String alias, String expiresAt) {
        Map<String, ?> body = expiresAt == null
                ? Map.of("originalUrl", url, "alias", alias)
                : Map.of("originalUrl", url, "alias", alias, "expiresAt", expiresAt);
        ResponseEntity<Map> created = restTemplate.exchange(
                RequestEntity.post("/api/links").contentType(MediaType.APPLICATION_JSON).body(body),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
