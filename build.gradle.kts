plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "6.25.0"
}

group = "com.javaded78"
version = "0.0.1-SNAPSHOT"
description = "URL-Shorter"

// Security override (Sprint 22): Spring Boot 4.1.1 manages Tomcat 11.0.24,
// which carries three CRITICAL CVEs fixed in 11.0.25 — pinned explicitly
// until a Boot patch release manages it. Verified with Trivy (zero
// CRITICAL findings on the production image).
extra["tomcat.version"] = "11.0.25"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Persistence: PostgreSQL + JPA ---
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    // --- Caching: Redis ---
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // --- Migrations: Flyway ---
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // --- Web / REST API + OpenAPI ---
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    // --- Bean Validation (request DTOs) ---
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // --- Observability: health / connectivity checks ---
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // --- Observability: Prometheus exposition (Sprint 29) ---
    implementation("io.micrometer:micrometer-registry-prometheus")

    // --- Multi-instance task serialization (Schedulock) ---
    // JdbcLockProvider on the existing PostgreSQL source of truth. The 7.x line targets
    // Spring Framework 7 (Boot 4.x); the 6.x line is for Spring 6 and must not be used here.
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")

    // --- Nullability: JSpecify type-level annotations (@NullMarked/@Nullable) ---
    // Maven group is org.jspecify; the annotation *package* is net.jspecify.annotations.
    implementation("org.jspecify:jspecify:0.3.0")

    // --- Code generation: Lombok + config metadata ---
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // --- Tests: Spring slice starters ---
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

    // --- Tests: Testcontainers (PostgreSQL, Redis) ---
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")

    // --- Tests: JUnit Platform + Lombok ---
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // TestRestTemplate auto-configuration needs RestTemplateBuilder (Boot 4 split artifact)
    testImplementation("org.springframework.boot:spring-boot-restclient")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}

// Unit test task: excludes Testcontainer-bearing tests (UrlShorterApplicationTests,
// LinkRepositoryTest) so it runs on pure JVM without Docker.
tasks.register<Test>("unitTest") {
    description = "Runs pure-JVM unit tests (no Docker/Testcontainers required)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Spotless: Google Java Format + Checkstyle validation (Sprint 33)
spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.22.0")
        // Fix imports that are out of alphabetical order
        importOrder()
        // Remove unused imports
        removeUnusedImports()
        // Standard whitespace
        trimTrailingWhitespace()
        endWithNewline()
    }
}
