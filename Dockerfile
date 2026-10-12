# syntax=docker/dockerfile:1
# Reproducible, hardened application image (Sprint 21).
#
# Multi-stage: a JDK builds the Spring Boot fat jar; only a JRE ships at runtime.
# The fat jar is unpacked into a classic exploded multi-jar layout (deps -> lib/,
# application classes -> classes/) in the JDK build stage — the JRE runtime
# image ships those two directories, so the app boots directly on its real main
# class through the JVM classpath, with no JarLauncher indirection and no -jarmode
# (that launcher option was dropped in JDK 25).
#
# The app runs as a dedicated non-root user; configuration arrives exclusively
# through environment variables (see docker-compose.yml / AGENTS.md §7).

FROM gradle:9.7-jdk25-alpine AS build
WORKDIR /workspace
# dependency layer cache: only a build-file change re-resolves dependencies
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon >/dev/null 2>&1 || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test
# Explode the Boot fat jar into a classic multi-jar layout (the JDK build
# stage has `jar`; the JRE runtime stage does not):
#   lib/     -> the dependency jars (referenced on the JVM classpath)
#   classes/ -> the application classes + resources (incl. Flyway migrations)
WORKDIR /exploded
RUN jar xf /workspace/build/libs/*.jar \
    && mkdir -p lib classes \
    && mv -- BOOT-INF/lib/* lib/ \
    && mv -- BOOT-INF/classes/* classes/ \
    && rm -rf META-INF BOOT-INF org

# Runtime: JRE only, non-root.
FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /exploded/lib /app/lib
COPY --from=build /exploded/classes /app/classes
USER app
# JVM: cap the heap at 75% of the container memory so virtual threads do not
# OOM the container. -Xshare:auto enables class-data sharing for a faster cold
# start; it is best-effort — a stale CDS archive must never abort startup.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Xshare:auto"
EXPOSE 8080
ENV SERVER_PORT=8080
HEALTHCHECK --start-period=90s --interval=10s --timeout=5s --retries=12 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp 'classes:lib/*' com.javaded78.urlshorter.UrlShorterApplication"]
