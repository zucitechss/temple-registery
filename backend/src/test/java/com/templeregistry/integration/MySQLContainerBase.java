package com.templeregistry.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared Testcontainers MySQL base for all {@code @SpringBootTest} integration tests.
 *
 * <p>Starts a single MySQL 8.0 container per test-class lifecycle (static field)
 * and registers its connection properties via {@code @DynamicPropertySource}.
 * Flyway migrations run automatically on first context start.
 *
 * <p>Usage:
 * <pre>{@code
 * @SpringBootTest
 * @ActiveProfiles("test")
 * class MyIT extends MySQLContainerBase { ... }
 * }</pre>
 *
 * <p>{@link Testcontainers#disabledWithoutDocker()} is {@code true} so that the test
 * is silently skipped in environments without Docker rather than failing.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class MySQLContainerBase {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_test")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withReuse(true);   // reuse across ITs in the same JVM run to speed CI

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // application-test.yml defaults to in-memory H2 — point everything at the container instead
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        // Let Flyway own the schema — never let Hibernate recreate it
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Drop the TiDB-only init SQL from application.yml — plain MySQL rejects it
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SELECT 1");
        // JWT keys (test stubs — keys must exist under src/test/resources/keys/)
        registry.add("app.jwt.private-key-path", () -> "classpath:keys/jwt-private.pem");
        registry.add("app.jwt.public-key-path",  () -> "classpath:keys/jwt-public.pem");
        // AWS/S3 stubs — no real bucket needed for unit/integration tests
        registry.add("cloud.aws.s3.bucket-name",   () -> "test-bucket");
        registry.add("cloud.aws.region.static",    () -> "ap-south-1");
        // Encryption key (32-byte AES-256 test key)
        registry.add("app.encryption.aes-key",     () -> "12345678901234567890123456789012");
        // Disable email sending in tests
        registry.add("spring.mail.enabled",        () -> "false");
    }
}
