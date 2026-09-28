# syntax=docker/dockerfile:1
# Reproducible application image (Sprint 21): multi-stage — Gradle builds
# the fat jar in a JDK image, only the JRE layer ships. The app runs as a
# dedicated non-root user; configuration arrives exclusively through
# environment variables (see docker-compose.yml / AGENTS.md §7).

FROM gradle:9.7-jdk25-alpine AS build
WORKDIR /workspace
# dependency layer cache: only a build-file change re-resolves dependencies
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon >/dev/null 2>&1 || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENV SERVER_PORT=8080
ENTRYPOINT ["java", "-jar", "app.jar"]
