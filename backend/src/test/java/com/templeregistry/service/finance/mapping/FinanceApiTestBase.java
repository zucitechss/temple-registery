package com.templeregistry.service.finance.mapping;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A full application context on a real MySQL, for the finance admin API tests (FIN-054A).
 *
 * <p>Deliberately not {@code MySQLContainerBase}. That base sets {@code ddl-auto=validate}, and
 * validation currently fails on {@code declaration_clarifications.field_names_json} — the
 * pre-existing FIN-X-001 schema drift, which has nothing to do with finance and which every
 * {@code @SpringBootTest} extending that base is already failing on. Setting {@code ddl-auto=none}
 * is the same thing the finance pipeline suites do, and for the same reason: Flyway owns the
 * schema here, so validation adds nothing except somebody else's unfixed defect.
 *
 * <p>A full context rather than {@code @DataJpaTest} because the thing being tested is
 * authorization. {@code @PreAuthorize} on a service that is never proxied is an annotation, not a
 * control, and a test that cannot tell the two apart is not worth writing.
 *
 * <p>The container is a singleton started once and never stopped, rather than a {@code @Container}
 * field. Spring caches one application context across both subclasses because their configuration
 * is identical, while a {@code @Container} field is started and stopped per test class — so the
 * second class would run against a cached context still pointing at the first class's stopped
 * container, and every test in it would fail on a connection timeout. The JVM reclaims the
 * container when it exits.
 */
@Testcontainers(disabledWithoutDocker = true)
abstract class FinanceApiTestBase {

    static final MySQLContainer<?> MYSQL;

    static {
        MYSQL = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("temple_registry_fin_admin")
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
