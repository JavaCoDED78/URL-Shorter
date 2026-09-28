package com.javaded78.urlshorter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared test infrastructure (Task 15.1).
 *
 * <p>Lifecycle: the containers are Spring-managed singletons — Boot's
 * Testcontainers lifecycle post-processor starts them with the application
 * context and stops them when it closes.
 *
 * <p>Dynamic properties: {@code @ServiceConnection} detects PostgreSQL and
 * Redis on the mapped ports and injects datasource/connection-factory
 * settings, so no manual {@code @DynamicPropertySource} is needed.
 *
 * <p>Clean state: Spring caches application contexts per test class
 * configuration — every IT class that imports this configuration gets its
 * own fresh containers, and tests within a class use unique aliases/codes.
 *
 * <p>Image versions are pinned (postgres:17, redis:7) to match the
 * production-like stacks in docker-compose and keep the suite reproducible.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
	}

	// 'resource' suppresses the "AutoCloseable used without try-with-resources" inspection:
	// these containers are Spring-managed singletons, and Spring Boot's
	// TestcontainersLifecycleBeanPostProcessor starts/stops them with the application
	// context, so try-with-resources would be incorrect here.
	@SuppressWarnings("resource")
	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
	}

}
