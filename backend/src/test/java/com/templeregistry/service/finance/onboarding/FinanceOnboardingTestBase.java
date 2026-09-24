package com.templeregistry.service.finance.onboarding;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A full application context on a real MySQL, for the onboarding tests (FIN-140 slice 140-A).
 *
 * <p>Deliberately mirrors {@code FinanceApiTestBase} rather than extending it: that class is
 * package-private in the mapping test package, and widening it to share would change a file this
 * slice has no business changing. The reasoning it records applies unchanged here —
 * {@code ddl-auto=none} because Flyway owns the schema and {@code validate} would fail on the
 * pre-existing FIN-X-001 drift in a table finance does not own; a full context rather than
 * {@code @DataJpaTest} because the thing under test is authorization, and {@code @PreAuthorize} on
 * an unproxied service is an annotation rather than a control.
 *
 * <p>The container is a static singleton started once, not a {@code @Container} field: Spring
 * caches one context across both subclasses, and a per-class container would leave the second
 * subclass talking to a stopped one.
 */
@Testcontainers(disabledWithoutDocker = true)
abstract class FinanceOnboardingTestBase {

    static final MySQLContainer<?> MYSQL;

    static {
        MYSQL = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("temple_registry_fin_onboarding")
                .withUsername("test")
                .withPassword("test");
        if (DockerClientFactory.instance().isDockerAvailable()) {
            MYSQL.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SELECT 1");
        registry.add("app.jwt.private-key-path", () -> "classpath:keys/jwt-private.pem");
        registry.add("app.jwt.public-key-path", () -> "classpath:keys/jwt-public.pem");
        registry.add("cloud.aws.s3.bucket-name", () -> "test-bucket");
        registry.add("cloud.aws.region.static", () -> "ap-south-1");
        registry.add("app.encryption.aes-key", () -> "12345678901234567890123456789012");
        registry.add("spring.mail.enabled", () -> "false");
    }
}
