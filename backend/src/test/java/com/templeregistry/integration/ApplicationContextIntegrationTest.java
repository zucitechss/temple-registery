package com.templeregistry.integration;

import com.templeregistry.TempleRegistryApplication;
import com.templeregistry.service.impl.auth.RsaTestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration smoke test: verifies that the Spring context loads successfully
 * against a real MySQL instance provided by Testcontainers.
 * Flyway runs all migrations automatically on start.
 */
@SpringBootTest(classes = TempleRegistryApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class ApplicationContextIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_test")
            .withUsername("test")
            .withPassword("test");

    private static final RsaTestKeys TEST_JWT_KEYS = RsaTestKeys.generate();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Drop the TiDB-only init SQL from application-dev.yml — plain MySQL rejects it
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SELECT 1");
        // Generated per run and injected as PEM — no committed key file (C-1).
        registry.add("app.jwt.private-key", TEST_JWT_KEYS::privateKeyPem);
        registry.add("app.jwt.public-key", TEST_JWT_KEYS::publicKeyPem);
        registry.add("app.jwt.private-key-path", () -> "");
        registry.add("app.jwt.public-key-path", () -> "");
        // Verifies the production Flyway location set: schema + reference data, no dev seed (C-5).
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("cloud.aws.s3.bucket-name", () -> "test-bucket");
        registry.add("cloud.aws.region.static", () -> "ap-south-1");
        registry.add("app.encryption.aes-key", () -> "12345678901234567890123456789012");
    }

    @Test
    void should_loadApplicationContext_when_migrationSucceeds() {
        assertThat(mysql.isRunning()).isTrue();
    }
}
