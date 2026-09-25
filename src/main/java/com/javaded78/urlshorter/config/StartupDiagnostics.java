package com.javaded78.urlshorter.config;

import java.sql.Connection;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Dev/test-only startup connectivity diagnostics.
 *
 * <p>Pings PostgreSQL and Redis once the context is ready and logs the outcome.
 * Never logs credentials, connection URLs or other sensitive configuration —
 * only product/version-level facts needed to confirm connectivity.
 *
 * <p>Active only in {@code local} and {@code test} profiles; production
 * observability is provided by Actuator health indicators instead.
 */
@Component
@Profile({"local", "test"})
public class StartupDiagnostics implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupDiagnostics.class);

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    public StartupDiagnostics(DataSource dataSource, RedisConnectionFactory redisConnectionFactory) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        checkPostgres();
        checkRedis();
    }

    private void checkPostgres() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String version = connection.getMetaData().getDatabaseProductVersion();
            log.info("Startup diagnostics: PostgreSQL OK ({} {})", product, version);
        } catch (Exception ex) {
            log.warn("Startup diagnostics: PostgreSQL UNAVAILABLE ({})", ex.getMessage());
        }
    }

    private void checkRedis() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            String pong = connection.ping();
            log.info("Startup diagnostics: Redis OK (PING -> {})", pong);
        } catch (Exception ex) {
            log.warn("Startup diagnostics: Redis UNAVAILABLE ({})", ex.getMessage());
        }
    }
}
