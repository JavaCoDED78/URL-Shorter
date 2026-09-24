package com.javaded78.urlshorter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	// 'resource' suppresses the "AutoCloseable used without try-with-resources" inspection:
	// these containers are Spring-managed singletons, and Spring Boot's
	// TestcontainersLifecycleBeanPostProcessor starts/stops them with the application
	// context, so try-with-resources would be incorrect here.
	@SuppressWarnings("resource")
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
	}

	@SuppressWarnings("resource")
	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);
	}

}
